package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end happy path with the in-memory store: same key + same body twice
 * → first call hits the controller, second call replays. Different body
 * with the same key → 422 mismatch. No key → no tracking, controller runs
 * twice.
 *
 * <p>Tests are exclude from production builds via Gradle's standard layout
 * (only main is published). They run on every CI build to catch regressions
 * in the filter / store contract.
 */
@SpringBootTest(
    classes = InMemoryFilterIntegrationTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.idempotency.enabled=true",
        "spring.idempotency.ttl=1h",
        "spring.idempotency.lock-timeout=5s",
        // spring-security is on the test classpath for the principal-binding
        // tests; exclude its auto-config here so this test keeps running against
        // unsecured endpoints (anonymous → bare key, behaviour unchanged).
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"
    }
)
class InMemoryFilterIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    InMemoryIdempotencyStore store;

    @Autowired
    TestApp.PaymentController controller;

    @AfterEach
    void resetState() {
        store.clear();
        controller.callCount.set(0);
    }

    @Test
    void sameKeySameBody_replaysFromCache() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "test-key-001");
        HttpEntity<String> request = new HttpEntity<>("{\"amount\":1000}", headers);

        ResponseEntity<String> first = http.exchange(
            "http://localhost:" + port + "/payments", HttpMethod.POST, request, String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getFirst("Idempotency-Replayed")).isNull();
        assertThat(controller.callCount.get()).isEqualTo(1);

        ResponseEntity<String> second = http.exchange(
            "http://localhost:" + port + "/payments", HttpMethod.POST, request, String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(second.getBody()).isEqualTo(first.getBody());
        // Critical: the controller was NOT invoked again.
        assertThat(controller.callCount.get()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBody_returns422Mismatch() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "test-key-002");

        ResponseEntity<String> first = http.exchange(
            "http://localhost:" + port + "/payments", HttpMethod.POST,
            new HttpEntity<>("{\"amount\":100}", headers), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.callCount.get()).isEqualTo(1);

        ResponseEntity<String> second = http.exchange(
            "http://localhost:" + port + "/payments", HttpMethod.POST,
            new HttpEntity<>("{\"amount\":999999}", headers), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(second.getHeaders().getFirst("Idempotency-Mismatch")).isEqualTo("true");
        // Critical: the controller was NOT invoked for the mismatched request.
        assertThat(controller.callCount.get()).isEqualTo(1);
    }

    @Test
    void noKey_skipsIdempotency() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{\"amount\":1}", headers);

        http.exchange("http://localhost:" + port + "/payments", HttpMethod.POST, request, String.class);
        http.exchange("http://localhost:" + port + "/payments", HttpMethod.POST, request, String.class);

        // Without an Idempotency-Key header, every request runs the controller.
        assertThat(controller.callCount.get()).isEqualTo(2);
    }

    @Test
    void getRequest_isNotTracked() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "would-be-tracked");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        http.exchange("http://localhost:" + port + "/ping", HttpMethod.GET, request, String.class);
        http.exchange("http://localhost:" + port + "/ping", HttpMethod.GET, request, String.class);

        // GET is outside spring.idempotency.methods (POST/PUT/PATCH).
        assertThat(controller.pingCount.get()).isEqualTo(2);
    }

    @Test
    void invalidKey_returns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "has spaces (illegal)");
        HttpEntity<String> request = new HttpEntity<>("{\"amount\":1}", headers);

        ResponseEntity<String> response = http.exchange(
            "http://localhost:" + port + "/payments", HttpMethod.POST, request, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("INVALID_IDEMPOTENCY_KEY");
        assertThat(controller.callCount.get()).isZero();
    }

    /** Minimal Spring Boot app used by the test. */
    @SpringBootApplication
    static class TestApp {
        public static void main(String[] args) {
            SpringApplication.run(TestApp.class, args);
        }

        @Bean
        public IdempotencyStore store() {
            return new InMemoryIdempotencyStore();
        }

        /**
         * Picked up by component scanning under {@code @SpringBootApplication}.
         * Do NOT also register it as a {@code @Bean} or Spring sees the bean
         * twice and refuses to start with "Ambiguous mapping".
         */
        @RestController
        static class PaymentController {
            final AtomicInteger callCount = new AtomicInteger();
            final AtomicInteger pingCount = new AtomicInteger();

            @PostMapping("/payments")
            public Map<String, Object> pay(@RequestBody Map<String, Object> body) {
                int n = callCount.incrementAndGet();
                return Map.of("ok", true, "callNumber", n, "echo", body);
            }

            @GetMapping("/ping")
            public String ping() {
                pingCount.incrementAndGet();
                return "pong";
            }
        }
    }
}
