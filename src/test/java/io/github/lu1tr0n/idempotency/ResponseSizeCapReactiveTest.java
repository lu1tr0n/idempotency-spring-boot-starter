package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
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
 * Reactive parity for {@code max-response-size}: an over-cap response reaches the
 * client in full but is not cached (handler re-runs on retry); an under-cap
 * response is cached and replayed.
 */
@SpringBootTest(
    classes = ResponseSizeCapReactiveTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.idempotency.max-response-size=64B",
        "spring.main.web-application-type=reactive",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration"
    })
class ResponseSizeCapReactiveTest {

    @Autowired
    WebTestClient client;

    @Autowired
    PadController controller;

    @Autowired
    InMemoryIdempotencyStore store;

    @AfterEach
    void reset() {
        controller.reset();
        store.clear();
    }

    @Test
    void overCapResponse_streamedInFull_butNotCached() {
        String bigPad = "x".repeat(200);
        client.post().uri("/op")
            .header("Idempotency-Key", "k-1")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"pad\":200}")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().doesNotExist("Idempotency-Replayed")
            .expectBody().jsonPath("$.pad").isEqualTo(bigPad);

        client.post().uri("/op")
            .header("Idempotency-Key", "k-1")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"pad\":3}")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().doesNotExist("Idempotency-Replayed");

        assertThat(controller.calls()).isEqualTo(2);
    }

    @Test
    void underCapResponse_isCachedAndReplayed() {
        client.post().uri("/op")
            .header("Idempotency-Key", "k-2")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"pad\":3}")
            .exchange()
            .expectStatus().isOk();

        client.post().uri("/op")
            .header("Idempotency-Key", "k-2")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"pad\":3}")
            .exchange()
            .expectStatus().isOk()
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
        PadController padController() {
            return new PadController();
        }
    }

    @RestController
    static class PadController {
        private final AtomicInteger calls = new AtomicInteger();

        @PostMapping("/op")
        public Mono<Map<String, Object>> op(@RequestBody Map<String, Object> body) {
            calls.incrementAndGet();
            int pad = body.get("pad") instanceof Number num ? num.intValue() : 0;
            return Mono.just(Map.of("pad", "x".repeat(pad)));
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }
    }
}
