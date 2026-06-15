package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the reactive {@code IdempotencyWebFilter}. Same key +
 * same body → second request replays. Different body with same key → 422.
 * No key → no tracking.
 */
@SpringBootTest(
    classes = WebFluxFilterIntegrationTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.idempotency.enabled=true",
        "spring.idempotency.ttl=1h",
        "spring.idempotency.lock-timeout=5s",
        "spring.main.web-application-type=reactive"
    }
)
class WebFluxFilterIntegrationTest {

    @Autowired
    WebTestClient client;

    @Autowired
    InMemoryIdempotencyStore store;

    @Autowired
    ReactivePaymentController controller;

    @AfterEach
    void reset() {
        store.clear();
        controller.reset();
    }

    @Test
    void sameKey_sameBody_replays() {
        String body = "{\"amount\":42}";
        client.post().uri("/payments")
            .header("Idempotency-Key", "test-key-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk();

        client.post().uri("/payments")
            .header("Idempotency-Key", "test-key-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("Idempotency-Replayed", "true");

        assertThat(controller.callCount()).isEqualTo(1);
    }

    @Test
    void sameKey_differentBody_returns422() {
        client.post().uri("/payments")
            .header("Idempotency-Key", "test-key-2")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"amount\":100}")
            .exchange()
            .expectStatus().isOk();

        client.post().uri("/payments")
            .header("Idempotency-Key", "test-key-2")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"amount\":999}")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(controller.callCount()).isEqualTo(1);
    }

    @Test
    void noKey_passesThrough() {
        client.post().uri("/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"amount\":1}")
            .exchange()
            .expectStatus().isOk();
        client.post().uri("/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"amount\":1}")
            .exchange()
            .expectStatus().isOk();

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
        ReactivePaymentController paymentController() {
            return new ReactivePaymentController();
        }
    }

    @RestController
    static class ReactivePaymentController {

        private final AtomicInteger calls = new AtomicInteger();

        @PostMapping("/payments")
        public Mono<Map<String, Object>> pay(@RequestBody Map<String, Object> body) {
            int n = calls.incrementAndGet();
            return Mono.just(Map.of("call", n, "echo", body));
        }

        int callCount() { return calls.get(); }

        void reset() { calls.set(0); }
    }
}
