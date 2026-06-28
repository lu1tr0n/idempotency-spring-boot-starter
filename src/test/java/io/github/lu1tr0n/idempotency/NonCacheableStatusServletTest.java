package io.github.lu1tr0n.idempotency;

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

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code non-cacheable-statuses}: a listed status (400) releases the lock so the
 * same key is retryable, while an unlisted status (409) is still cached and
 * replayed.
 */
@SpringBootTest(
    classes = NonCacheableStatusServletTest.TestApp.class,
    properties = {
        "spring.idempotency.non-cacheable-statuses=400",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
            + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
    })
@AutoConfigureMockMvc
class NonCacheableStatusServletTest {

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
    void listedStatus_releasesLock_sameKeyRetryable() throws Exception {
        // First call returns the non-cacheable 400 → lock released, not saved.
        mvc.perform(post("/op")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":400}"))
            .andExpect(status().isBadRequest())
            .andExpect(header().doesNotExist("Idempotency-Replayed"));

        // Same key, corrected body → the handler RUNS AGAIN (not a replayed 400),
        // and a released key has no stored hash so the different body is fine.
        mvc.perform(post("/op")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":200}"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Idempotency-Replayed"));

        org.assertj.core.api.Assertions.assertThat(controller.calls()).isEqualTo(2);
    }

    @Test
    void unlistedStatus_isStillCachedAndReplayed() throws Exception {
        mvc.perform(post("/op")
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":409}"))
            .andExpect(status().isConflict());

        // Replays the cached 409 — handler does NOT run again.
        mvc.perform(post("/op")
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":409}"))
            .andExpect(status().isConflict())
            .andExpect(header().string("Idempotency-Replayed", "true"));

        org.assertj.core.api.Assertions.assertThat(controller.calls()).isEqualTo(1);
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
            int status = body.get("status") instanceof Number num ? num.intValue() : 200;
            return ResponseEntity.status(status).body(Map.of("call", n));
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }
    }
}
