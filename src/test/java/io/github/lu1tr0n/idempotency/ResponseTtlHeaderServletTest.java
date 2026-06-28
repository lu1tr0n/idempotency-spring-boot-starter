package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code response-ttl-header}: a handler-emitted {@code Idempotency-Persist-For}
 * overrides the global TTL for one record, is clamped to {@code max}, falls back
 * to the default on a malformed value, and is stripped from both the wire
 * response and the stored record. Principal binding is disabled so the stored
 * key equals the raw key and the record can be looked up directly.
 */
@SpringBootTest(
    classes = ResponseTtlHeaderServletTest.TestApp.class,
    properties = {
        "spring.idempotency.ttl=24h",
        "spring.idempotency.response-ttl-header.enabled=true",
        "spring.idempotency.response-ttl-header.max=1h",
        "spring.idempotency.principal-binding=disabled",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
            + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
    })
@AutoConfigureMockMvc
class ResponseTtlHeaderServletTest {

    private static final String HEADER = "Idempotency-Persist-For";

    @Autowired
    MockMvc mvc;

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
    void validOverride_appliesShortTtl_stripsHeader_fromWireAndRecord() throws Exception {
        mvc.perform(post("/op")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"persistFor\":\"60\"}"))
            .andExpect(status().isOk())
            // Directive stripped from the wire.
            .andExpect(header().doesNotExist(HEADER));

        Instant exp = expiry("k-1");
        // ~60s, decisively shorter than the 24h default and the 1h ceiling.
        assertThat(exp).isBefore(Instant.now().plusSeconds(600));
        assertThat(exp).isAfter(Instant.now().plusSeconds(30));
        // Directive not persisted, so a replay can never re-assert it.
        assertThat(headerInRecord("k-1")).isFalse();

        // Replay: handler does not run again, still no directive on the wire.
        mvc.perform(post("/op")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"persistFor\":\"60\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(header().doesNotExist(HEADER));
        assertThat(controller.calls()).isEqualTo(1);
    }

    @Test
    void overMax_clampsDownToCeiling() throws Exception {
        mvc.perform(post("/op")
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"persistFor\":\"999999\"}"))
            .andExpect(status().isOk());

        Instant exp = expiry("k-2");
        // Clamped to the 1h max, not the requested ~11.5 days.
        assertThat(exp).isBefore(Instant.now().plusSeconds(3600 + 60));
        assertThat(exp).isAfter(Instant.now().plusSeconds(3600 - 60));
    }

    @Test
    void malformedValue_fallsBackToDefaultTtl() throws Exception {
        mvc.perform(post("/op")
                .header("Idempotency-Key", "k-3")
                .contentType(MediaType.APPLICATION_JSON).content("{\"persistFor\":\"abc\"}"))
            .andExpect(status().isOk());

        // Falls back to the 24h default — well beyond the 1h ceiling.
        assertThat(expiry("k-3")).isAfter(Instant.now().plus(java.time.Duration.ofHours(12)));
    }

    @Test
    void noDirective_usesDefaultTtl() throws Exception {
        mvc.perform(post("/op")
                .header("Idempotency-Key", "k-4")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk());

        assertThat(expiry("k-4")).isAfter(Instant.now().plus(java.time.Duration.ofHours(12)));
    }

    private Instant expiry(String key) {
        return store.findRecord(IdempotencyKey.of(key)).orElseThrow().expiresAt();
    }

    private boolean headerInRecord(String key) {
        return store.findRecord(IdempotencyKey.of(key)).orElseThrow().headers().keySet().stream()
            .anyMatch(HEADER::equalsIgnoreCase);
    }

    @SpringBootApplication
    static class TestApp {
        @Bean
        InMemoryIdempotencyStore store() {
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
        public ResponseEntity<Map<String, Object>> op(@RequestBody Map<String, Object> body) {
            int n = calls.incrementAndGet();
            ResponseEntity.BodyBuilder rb = ResponseEntity.status(200);
            Object persistFor = body.get("persistFor");
            if (persistFor != null) {
                rb.header(HEADER, persistFor.toString());
            }
            return rb.body(Map.of("call", n));
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }
    }
}
