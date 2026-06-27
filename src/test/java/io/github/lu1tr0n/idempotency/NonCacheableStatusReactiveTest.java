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
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reactive parity for {@code non-cacheable-statuses}: a listed 400 releases the
 * lock (handler re-runs on retry); an unlisted 409 is cached and replayed.
 */
@SpringBootTest(
    classes = NonCacheableStatusReactiveTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.idempotency.non-cacheable-statuses=400",
        "spring.main.web-application-type=reactive",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration"
    })
class NonCacheableStatusReactiveTest {

    @Autowired
    WebTestClient client;

    @Autowired
    OpController controller;

    @Autowired
    InMemoryIdempotencyStore store;

    @AfterEach
    void reset() {
        controller.reset();
        store.clear();
    }

    @Test
    void listedStatus_releasesLock_sameKeyRetryable() {
        client.post().uri("/op")
            .header("Idempotency-Key", "k-1")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"status\":400}")
            .exchange()
            .expectStatus().isBadRequest()
            .expectHeader().doesNotExist("Idempotency-Replayed");

        client.post().uri("/op")
            .header("Idempotency-Key", "k-1")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"status\":200}")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().doesNotExist("Idempotency-Replayed");

        assertThat(controller.calls()).isEqualTo(2);
    }

    @Test
    void unlistedStatus_isStillCachedAndReplayed() {
        client.post().uri("/op")
            .header("Idempotency-Key", "k-2")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"status\":409}")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT);

        client.post().uri("/op")
            .header("Idempotency-Key", "k-2")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"status\":409}")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectHeader().valueEquals("Idempotency-Replayed", "true");

        assertThat(controller.calls()).isEqualTo(1);
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
        OpController opController() {
            return new OpController();
        }
    }

    @RestController
    static class OpController {
        private final AtomicInteger calls = new AtomicInteger();

        @PostMapping("/op")
        public Mono<ResponseEntity<Map<String, Object>>> op(@RequestBody Map<String, Object> body) {
            int n = calls.incrementAndGet();
            int status = body.get("status") instanceof Number num ? num.intValue() : 200;
            return Mono.just(ResponseEntity.status(status).body(Map.of("call", n)));
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }
    }
}
