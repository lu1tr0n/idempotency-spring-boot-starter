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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Backward-compat: with {@code response-ttl-header} off (the default), a handler
 * emitting {@code Idempotency-Persist-For} sees it pass through to the client
 * unchanged and the record keeps the global TTL — zero behaviour change.
 */
@SpringBootTest(
    classes = ResponseTtlHeaderDisabledServletTest.TestApp.class,
    properties = {
        "spring.idempotency.ttl=24h",
        // response-ttl-header.enabled defaults to false — not set here on purpose.
        "spring.idempotency.principal-binding=disabled",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
            + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
    })
@AutoConfigureMockMvc
class ResponseTtlHeaderDisabledServletTest {

    private static final String HEADER = "Idempotency-Persist-For";

    @Autowired
    MockMvc mvc;

    @Autowired
    InMemoryIdempotencyStore store;

    @AfterEach
    void reset() {
        store.clear();
    }

    @Test
    void disabled_headerPassesThrough_andDefaultTtlApplies() throws Exception {
        mvc.perform(post("/op")
                .header("Idempotency-Key", "k-off")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            // Feature off → the header is an ordinary response header, not consumed.
            .andExpect(header().string(HEADER, "60"));

        // Default 24h TTL — the directive had no effect.
        Instant exp = store.findRecord(IdempotencyKey.of("k-off")).orElseThrow().expiresAt();
        assertThat(exp).isAfter(Instant.now().plus(Duration.ofHours(12)));
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
        @PostMapping("/op")
        public ResponseEntity<Map<String, Object>> op(@RequestBody Map<String, Object> body) {
            return ResponseEntity.status(200).header(HEADER, "60").body(Map.of("ok", true));
        }
    }
}
