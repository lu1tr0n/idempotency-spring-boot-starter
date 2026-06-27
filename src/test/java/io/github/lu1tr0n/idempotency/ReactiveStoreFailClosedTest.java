package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Reactive parity for {@code failure-strategy}: with the default
 * {@code fail-closed}, a store outage on the WebFlux stack refuses the request
 * (503) rather than silently processing it without an idempotency guarantee.
 * Regression guard for the reactive fail-open divergence.
 */
@SpringBootTest(
    classes = ReactiveStoreFailClosedTest.TestApp.class,
    properties = {
        "spring.main.web-application-type=reactive",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration"
    })
class ReactiveStoreFailClosedTest {

    @Autowired
    ApplicationContext context;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).configureClient().build();
    }

    @Test
    void storeOutage_failsClosedWith503_byDefault() {
        client.post().uri("/payments")
            .header("Idempotency-Key", "k-1")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"amount\":1}")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            .expectBody().jsonPath("$.error.code").isEqualTo("IDEMPOTENCY_STORE_UNAVAILABLE");
    }

    @SpringBootApplication
    static class TestApp {
        @Bean
        IdempotencyStore store() {
            return new UnreachableStore();
        }

        @RestController
        static class PaymentController {
            @PostMapping("/payments")
            public Mono<Map<String, Object>> pay(@RequestBody Map<String, Object> body) {
                return Mono.just(Map.of("ok", true));
            }
        }
    }

    /** A store that is always down — every operation throws {@link IdempotencyStore.StoreException}. */
    static class UnreachableStore implements IdempotencyStore {
        @Override
        public Optional<IdempotencyRecord> findRecord(IdempotencyKey key) {
            throw new StoreException("store down", new RuntimeException("boom"));
        }

        @Override
        public Optional<LockToken> acquireLock(IdempotencyKey key, Duration lockTimeout) {
            throw new StoreException("store down", new RuntimeException("boom"));
        }

        @Override
        public void releaseLock(IdempotencyKey key, LockToken token) {
            throw new StoreException("store down", new RuntimeException("boom"));
        }

        @Override
        public void save(IdempotencyRecord record, LockToken token) {
            throw new StoreException("store down", new RuntimeException("boom"));
        }
    }
}
