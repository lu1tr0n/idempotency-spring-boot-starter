package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.annotation.Idempotent;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the {@code @Idempotent} aspect — the v0.0.3 surface.
 *
 * <p>The global filter is disabled in this test so the aspect is the only
 * dedup mechanism. This isolates aspect behaviour from the filter and
 * confirms the aspect alone is sufficient when consumers prefer per-method
 * opt-in over the global default.
 */
@SpringBootTest(
    classes = IdempotentAnnotationIntegrationTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.idempotency.enabled=true",
        // Restrict the global filter to methods we never call here, so the
        // aspect is the only thing that can dedup.
        "spring.idempotency.methods=DELETE",
        "spring.idempotency.ttl=1h",
        "spring.idempotency.lock-timeout=5s",
        // spring-security is on the test classpath for the principal-binding
        // tests; exclude its auto-config here so these pre-existing tests keep
        // running against unsecured endpoints (anonymous → bare key, unchanged).
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration"
    }
)
class IdempotentAnnotationIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    InMemoryIdempotencyStore store;

    @Autowired
    PaymentController controller;

    @AfterEach
    void resetStore() {
        store.clear();
        controller.reset();
    }

    @Test
    void spelKey_dedupsAcrossInvocations() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"orderId\":\"order-spel-1\",\"amount\":1000}";
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> r1 = http.exchange(
            "http://localhost:" + port + "/payments", HttpMethod.POST, request, Map.class);
        ResponseEntity<Map> r2 = http.exchange(
            "http://localhost:" + port + "/payments", HttpMethod.POST, request, Map.class);

        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.callCount()).isEqualTo(1);
        assertThat(r1.getBody()).isEqualTo(r2.getBody());
    }

    @Test
    void differentSpelKeys_bothExecute() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> r1 = http.exchange("http://localhost:" + port + "/payments",
            HttpMethod.POST,
            new HttpEntity<>("{\"orderId\":\"order-A\",\"amount\":1}", headers),
            Map.class);
        ResponseEntity<Map> r2 = http.exchange("http://localhost:" + port + "/payments",
            HttpMethod.POST,
            new HttpEntity<>("{\"orderId\":\"order-B\",\"amount\":2}", headers),
            Map.class);

        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.callCount()).isEqualTo(2);
    }

    @Test
    void asyncCompletableFuture_dedupsOnReplay() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"orderId\":\"order-async-1\",\"amount\":50}";
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> r1 = http.exchange(
            "http://localhost:" + port + "/payments/async", HttpMethod.POST, request, Map.class);
        ResponseEntity<Map> r2 = http.exchange(
            "http://localhost:" + port + "/payments/async", HttpMethod.POST, request, Map.class);

        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.callCount()).isEqualTo(1);
        assertThat(r1.getBody()).isEqualTo(r2.getBody());
    }

    @Test
    void disabledAnnotation_alwaysExecutes() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{\"orderId\":\"order-disabled\"}", headers);

        http.exchange("http://localhost:" + port + "/payments/risky",
            HttpMethod.POST, request, Map.class);
        http.exchange("http://localhost:" + port + "/payments/risky",
            HttpMethod.POST, request, Map.class);

        assertThat(controller.callCount()).isEqualTo(2);
    }

    @SpringBootApplication
    static class TestApp {
        public static void main(String[] args) {
            SpringApplication.run(TestApp.class, args);
        }

        @Bean
        InMemoryIdempotencyStore inMemoryStore() {
            return new InMemoryIdempotencyStore();
        }

        @Bean
        PaymentController paymentController() {
            return new PaymentController();
        }
    }

    @RestController
    static class PaymentController {

        private final AtomicInteger calls = new AtomicInteger();

        @PostMapping("/payments")
        @Idempotent(key = "#request.orderId", ttl = 5, timeUnit = TimeUnit.MINUTES)
        public PaymentResponse pay(@RequestBody PaymentRequest request) {
            int n = calls.incrementAndGet();
            return new PaymentResponse(request.orderId, "ok", n);
        }

        @PostMapping("/payments/risky")
        @Idempotent(enabled = false)
        public PaymentResponse risky(@RequestBody PaymentRequest request) {
            calls.incrementAndGet();
            return new PaymentResponse(request.orderId, "risky", calls.get());
        }

        @PostMapping("/payments/async")
        @Idempotent(key = "#request.orderId")
        public CompletableFuture<PaymentResponse> payAsync(@RequestBody PaymentRequest request) {
            int n = calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                new PaymentResponse(request.orderId, "async-ok", n));
        }

        public int callCount() { return calls.get(); }

        public void reset() { calls.set(0); }
    }

    public record PaymentRequest(String orderId, Integer amount) {
    }

    public record PaymentResponse(String orderId, String status, int callNumber) {
    }
}
