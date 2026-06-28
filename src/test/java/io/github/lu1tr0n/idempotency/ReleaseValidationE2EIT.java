package io.github.lu1tr0n.idempotency;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end release validation for v0.0.4. Boots a REAL Spring Boot app on a
 * real Tomcat (random port) backed by a real PostgreSQL whose schema is created
 * by the SHIPPED Flyway migration (the exact consumer opt-in path
 * {@code spring.flyway.locations=classpath:db/idempotency/flyway/postgresql}),
 * then drives it over real HTTP. Exercises the whole v0.0.4 surface together —
 * replay, payload mismatch, real concurrent-lock 409, {@code @RequireIdempotencyKey},
 * the request-body DoS cap, untracked passthrough, and Micrometer observations —
 * to catch any integration bug the per-feature MockMvc tests can't.
 */
@SpringBootTest(
    classes = ReleaseValidationE2EIT.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.idempotency.jdbc.auto-create-table=false", // schema comes from the shipped migration
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/idempotency/flyway/postgresql",
        "spring.idempotency.max-body-size=256B",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
            + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
    })
@Testcontainers
class ReleaseValidationE2EIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("idem_e2e").withUsername("e2e").withPassword("e2e");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Api api;

    @Autowired
    TestObservationRegistry observations;

    private HttpHeaders json(String key) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            h.set("Idempotency-Key", key);
        }
        return h;
    }

    @Test
    void schemaCameFromTheShippedFlywayMigration() {
        // Proves the app is genuinely running on the migration artifact we ship,
        // not the dev auto-create path (which is disabled above).
        Integer migrated = jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE description = ?",
            Integer.class, "create idempotency records");
        assertThat(migrated).isEqualTo(1);
    }

    @Test
    void replaysIdenticalRetry_executingExactlyOnce() {
        api.reset();
        HttpEntity<String> req = new HttpEntity<>("{\"amount\":100}", json("e2e-replay"));

        ResponseEntity<String> first = http.postForEntity("/pay", req, String.class);
        ResponseEntity<String> second = http.postForEntity("/pay", req, String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(first.getBody()).isEqualTo(second.getBody()); // byte-identical replay
        assertThat(api.pays.get()).isEqualTo(1);                 // handler ran once
    }

    @Test
    void sameKeyDifferentBody_returns422Mismatch() {
        HttpEntity<String> a = new HttpEntity<>("{\"amount\":1}", json("e2e-mismatch"));
        HttpEntity<String> b = new HttpEntity<>("{\"amount\":2}", json("e2e-mismatch"));

        http.postForEntity("/pay", a, String.class);
        ResponseEntity<String> mismatch = http.postForEntity("/pay", b, String.class);

        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mismatch.getHeaders().getFirst("Idempotency-Mismatch")).isEqualTo("true");
    }

    @Test
    void realConcurrentRequests_oneExecutes_otherGets409() throws Exception {
        api.reset();
        api.armGate();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            HttpEntity<String> req = new HttpEntity<>("{\"amount\":7}", json("e2e-race"));
            // Request A enters the handler and blocks there, holding the lock.
            Future<ResponseEntity<String>> a =
                pool.submit(() -> http.postForEntity("/slow", req, String.class));
            assertThat(api.entered.await(10, TimeUnit.SECONDS)).isTrue();

            // Request B arrives while A holds the lock → must get 409 lock-held.
            ResponseEntity<String> b = http.postForEntity("/slow", req, String.class);
            assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(b.getHeaders().getFirst("Retry-After")).isEqualTo("1");

            api.proceed.countDown(); // let A finish
            assertThat(a.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(api.slows.get()).isEqualTo(1); // only A executed
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void requireIdempotencyKey_missingKeyIsRejected400() {
        ResponseEntity<String> r = http.postForEntity(
            "/strict", new HttpEntity<>("{}", json(null)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).contains("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void requestBodyOverCap_isRejected413() {
        String big = "{\"data\":\"" + "x".repeat(400) + "\"}"; // > 256B cap
        ResponseEntity<String> r = http.postForEntity(
            "/pay", new HttpEntity<>(big, json("e2e-toobig")), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(r.getBody()).contains("IDEMPOTENCY_PAYLOAD_TOO_LARGE");
    }

    @Test
    void noKey_passesThroughUntracked() {
        api.reset();
        HttpEntity<String> req = new HttpEntity<>("{\"amount\":1}", json(null));
        http.postForEntity("/pay", req, String.class);
        http.postForEntity("/pay", req, String.class);
        assertThat(api.pays.get()).isEqualTo(2); // every call runs; nothing cached
    }

    @Test
    void emitsObservationsForRealTraffic() {
        http.postForEntity("/pay", new HttpEntity<>("{\"amount\":1}", json("e2e-obs")), String.class);
        TestObservationRegistryAssert.assertThat(observations)
            .hasAnObservationWithAKeyValue("idempotency.outcome", "executed_stored");
    }

    @SpringBootApplication
    static class App {
        public static void main(String[] args) {
            SpringApplication.run(App.class, args);
        }

        @Bean
        Api api() {
            return new Api();
        }

        @Bean
        TestObservationRegistry observationRegistry() {
            return TestObservationRegistry.create();
        }
    }

    @RestController
    static class Api {
        final AtomicInteger pays = new AtomicInteger();
        final AtomicInteger slows = new AtomicInteger();
        volatile CountDownLatch entered = new CountDownLatch(0);
        volatile CountDownLatch proceed = new CountDownLatch(0);

        void reset() {
            pays.set(0);
            slows.set(0);
        }

        void armGate() {
            entered = new CountDownLatch(1);
            proceed = new CountDownLatch(1);
        }

        @PostMapping("/pay")
        Map<String, Object> pay(@RequestBody Map<String, Object> body) {
            return Map.of("paid", true, "n", pays.incrementAndGet());
        }

        @PostMapping("/slow")
        Map<String, Object> slow(@RequestBody Map<String, Object> body) throws InterruptedException {
            slows.incrementAndGet();
            entered.countDown();             // signal "I hold the lock now"
            proceed.await(10, TimeUnit.SECONDS); // block so the concurrent retry sees the lock
            return Map.of("paid", true);
        }

        @PostMapping("/strict")
        @io.github.lu1tr0n.idempotency.annotation.RequireIdempotencyKey
        Map<String, Object> strict(@RequestBody Map<String, Object> body) {
            return Map.of("ok", true);
        }
    }
}
