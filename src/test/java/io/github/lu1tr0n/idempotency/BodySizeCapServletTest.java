package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code spring.idempotency.max-body-size}: a keyed request whose body exceeds
 * the cap is rejected 413 before any store work; smaller keyed bodies and
 * unkeyed requests (which are never buffered) are unaffected.
 */
@SpringBootTest(
    classes = BodySizeCapServletTest.TestApp.class,
    properties = {
        "spring.idempotency.max-body-size=64B",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"
    })
@AutoConfigureMockMvc
class BodySizeCapServletTest {

    private static final String OVER_CAP = "{\"data\":\"" + "x".repeat(128) + "\"}";
    private static final String UNDER_CAP = "{\"a\":1}";
    // {"a":"<pad>"} — 6 + pad + 2 bytes. 56 pad → exactly 64; 57 → 65.
    private static final String EXACTLY_64 = "{\"a\":\"" + "x".repeat(56) + "\"}";
    private static final String ONE_OVER_65 = "{\"a\":\"" + "x".repeat(57) + "\"}";

    @Autowired
    MockMvc mvc;

    @Test
    void keyedBodyOverCap_isRejectedWith413() throws Exception {
        mvc.perform(post("/echo")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content(OVER_CAP))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(content().string(Matchers.containsString("IDEMPOTENCY_PAYLOAD_TOO_LARGE")));
    }

    @Test
    void keyedBodyUnderCap_proceeds() throws Exception {
        mvc.perform(post("/echo")
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON).content(UNDER_CAP))
            .andExpect(status().isOk());
    }

    @Test
    void unkeyedBodyOverCap_isNotCapped() throws Exception {
        // No key → never buffered → the cap does not apply.
        mvc.perform(post("/echo")
                .contentType(MediaType.APPLICATION_JSON).content(OVER_CAP))
            .andExpect(status().isOk());
    }

    @Test
    void bodyExactlyAtCap_proceeds() throws Exception {
        mvc.perform(post("/echo")
                .header("Idempotency-Key", "k-64")
                .contentType(MediaType.APPLICATION_JSON).content(EXACTLY_64))
            .andExpect(status().isOk());
    }

    @Test
    void bodyOneByteOverCap_isRejected() throws Exception {
        mvc.perform(post("/echo")
                .header("Idempotency-Key", "k-65")
                .contentType(MediaType.APPLICATION_JSON).content(ONE_OVER_65))
            .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void rejectedOverCapRequest_leavesNoLockOrRecord() throws Exception {
        // Over-cap → 413 before acquireLock/save. Re-using the SAME key with a
        // valid small body must therefore proceed (200) — not 409 (stale lock)
        // nor a replay — proving the rejection took no lock and wrote no record.
        mvc.perform(post("/echo")
                .header("Idempotency-Key", "k-reuse")
                .contentType(MediaType.APPLICATION_JSON).content(OVER_CAP))
            .andExpect(status().isPayloadTooLarge());

        mvc.perform(post("/echo")
                .header("Idempotency-Key", "k-reuse")
                .contentType(MediaType.APPLICATION_JSON).content(UNDER_CAP))
            .andExpect(status().isOk());
    }

    @SpringBootApplication
    static class TestApp {
        @Bean
        IdempotencyStore store() {
            return new InMemoryIdempotencyStore();
        }

        @RestController
        static class EchoController {
            @PostMapping("/echo")
            public Map<String, Object> echo(@RequestBody Map<String, Object> body) {
                return Map.of("ok", true);
            }
        }
    }
}
