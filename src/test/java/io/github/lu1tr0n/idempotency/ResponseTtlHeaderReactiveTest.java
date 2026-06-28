package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reactive parity for {@code response-ttl-header}: the directive overrides the
 * TTL, clamps to {@code max}, and is stripped from wire + record — including the
 * empty-body commit path ({@code setComplete()}) that {@code writeWith}-based
 * stripping would miss, which is why the strip runs in {@code beforeCommit}.
 */
@SpringBootTest(
    classes = ResponseTtlHeaderReactiveTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.idempotency.ttl=24h",
        "spring.idempotency.response-ttl-header.enabled=true",
        "spring.idempotency.response-ttl-header.max=1h",
        "spring.idempotency.principal-binding=disabled",
        "spring.main.web-application-type=reactive",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration,"
            + "org.springframework.boot.actuate.autoconfigure.security.reactive.ReactiveManagementWebSecurityAutoConfiguration"
    })
class ResponseTtlHeaderReactiveTest {

    private static final String HEADER = "Idempotency-Persist-For";

    @Autowired
    WebTestClient client;

    @Autowired
    InMemoryIdempotencyStore store;

    @AfterEach
    void reset() {
        store.clear();
    }

    @Test
    void validOverride_appliesShortTtl_stripsHeader_fromWireAndRecord() {
        client.post().uri("/op")
            .header("Idempotency-Key", "k-1")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"persistFor\":\"60\"}")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().doesNotExist(HEADER);

        Instant exp = expiry("k-1");
        assertThat(exp).isBefore(Instant.now().plusSeconds(600));
        assertThat(exp).isAfter(Instant.now().plusSeconds(30));
        assertThat(headerInRecord("k-1")).isFalse();
    }

    @Test
    void overMax_clampsDownToCeiling() {
        client.post().uri("/op")
            .header("Idempotency-Key", "k-2")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"persistFor\":\"999999\"}")
            .exchange()
            .expectStatus().isOk();

        Instant exp = expiry("k-2");
        assertThat(exp).isBefore(Instant.now().plusSeconds(3600 + 60));
        assertThat(exp).isAfter(Instant.now().plusSeconds(3600 - 60));
    }

    @Test
    void emptyBodyResponse_directiveAppliedViaBeforeCommit() {
        // 204 No Content has no body, so it commits via setComplete() rather than
        // writeWith — the beforeCommit hook is what makes the strip + capture work.
        client.post().uri("/empty")
            .header("Idempotency-Key", "k-3")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
            .exchange()
            .expectStatus().isNoContent()
            .expectHeader().doesNotExist(HEADER);

        Instant exp = expiry("k-3");
        assertThat(exp).isBefore(Instant.now().plusSeconds(600));
        assertThat(exp).isAfter(Instant.now().plusSeconds(15));
        assertThat(headerInRecord("k-3")).isFalse();
    }

    @Test
    void malformedValue_fallsBackToDefaultTtl() {
        client.post().uri("/op")
            .header("Idempotency-Key", "k-4")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"persistFor\":\"abc\"}")
            .exchange()
            .expectStatus().isOk();

        assertThat(expiry("k-4")).isAfter(Instant.now().plus(Duration.ofHours(12)));
    }

    private Instant expiry(String key) {
        return awaitRecord(key).expiresAt();
    }

    private boolean headerInRecord(String key) {
        return awaitRecord(key).headers().keySet().stream().anyMatch(HEADER::equalsIgnoreCase);
    }

    /**
     * Reactive persistence happens in {@code doOnSuccess}, which fires after the
     * response returns to the client — so the record may not be in the store the
     * instant {@code exchange()} completes. Poll briefly for it.
     */
    private IdempotencyRecord awaitRecord(String key) {
        IdempotencyKey k = IdempotencyKey.of(key);
        for (int i = 0; i < 100; i++) {
            var found = store.findRecord(k);
            if (found.isPresent()) {
                return found.get();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Record for key '" + key + "' never persisted");
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
        @PostMapping("/op")
        public Mono<ResponseEntity<Map<String, Object>>> op(@RequestBody Map<String, Object> body) {
            ResponseEntity.BodyBuilder rb = ResponseEntity.status(200);
            Object persistFor = body.get("persistFor");
            if (persistFor != null) {
                rb.header(HEADER, persistFor.toString());
            }
            return Mono.just(rb.body(Map.of("ok", true)));
        }

        @PostMapping("/empty")
        public Mono<ResponseEntity<Void>> empty(@RequestBody Map<String, Object> body) {
            return Mono.just(ResponseEntity.noContent().header(HEADER, "45").build());
        }
    }
}
