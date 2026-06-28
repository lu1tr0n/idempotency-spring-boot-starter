package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

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

/**
 * Reactive parity for {@code spring.idempotency.max-body-size}: an over-cap
 * keyed body is rejected 413 via the limited {@code DataBufferUtils.join};
 * a smaller keyed body proceeds.
 */
@SpringBootTest(
    classes = BodySizeCapReactiveTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.idempotency.max-body-size=64B",
        "spring.main.web-application-type=reactive",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration,"
            + "org.springframework.boot.actuate.autoconfigure.security.reactive.ReactiveManagementWebSecurityAutoConfiguration"
    })
class BodySizeCapReactiveTest {

    private static final String OVER_CAP = "{\"data\":\"" + "x".repeat(128) + "\"}";
    private static final String UNDER_CAP = "{\"a\":1}";
    private static final String EXACTLY_64 = "{\"a\":\"" + "x".repeat(56) + "\"}";
    private static final String ONE_OVER_65 = "{\"a\":\"" + "x".repeat(57) + "\"}";

    @Autowired
    WebTestClient client;

    @Test
    void keyedBodyOverCap_isRejectedWith413() {
        client.post().uri("/echo")
            .header("Idempotency-Key", "k-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(OVER_CAP)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
            .expectBody().jsonPath("$.error.code").isEqualTo("IDEMPOTENCY_PAYLOAD_TOO_LARGE");
    }

    @Test
    void keyedBodyUnderCap_proceeds() {
        client.post().uri("/echo")
            .header("Idempotency-Key", "k-2")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(UNDER_CAP)
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    void bodyExactlyAtCap_proceeds() {
        client.post().uri("/echo")
            .header("Idempotency-Key", "k-64")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(EXACTLY_64)
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    void bodyOneByteOverCap_isRejected() {
        client.post().uri("/echo")
            .header("Idempotency-Key", "k-65")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ONE_OVER_65)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void unkeyedBodyOverCap_isNotCapped() {
        // No key → never buffered → cap does not apply.
        client.post().uri("/echo")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(OVER_CAP)
            .exchange()
            .expectStatus().isOk();
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

        @RestController
        static class EchoController {
            @PostMapping("/echo")
            public Mono<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
                return Mono.just(Map.of("ok", true));
            }
        }
    }
}
