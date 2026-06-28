package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.annotation.RequireIdempotencyKey;
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
 * The required-key check is tied to the configured
 * {@code spring.idempotency.header-name}, not the literal {@code Idempotency-Key}.
 */
@SpringBootTest(
    classes = RequireIdempotencyKeyCustomHeaderTest.TestApp.class,
    properties = {
        "spring.idempotency.header-name=X-Custom-Idem",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
            + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
    })
@AutoConfigureMockMvc
class RequireIdempotencyKeyCustomHeaderTest {

    @Autowired
    MockMvc mvc;

    @Test
    void missingCustomHeader_isRejected() throws Exception {
        mvc.perform(post("/pay")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(Matchers.containsString("IDEMPOTENCY_KEY_REQUIRED")))
            // The message names the configured header, not the default.
            .andExpect(content().string(Matchers.containsString("X-Custom-Idem")));
    }

    @Test
    void presentCustomHeader_proceeds() throws Exception {
        mvc.perform(post("/pay")
                .header("X-Custom-Idem", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
            .andExpect(status().isOk());
    }

    @SpringBootApplication
    static class TestApp {
        @Bean
        IdempotencyStore store() {
            return new InMemoryIdempotencyStore();
        }

        @RestController
        static class PaymentController {
            @PostMapping("/pay")
            @RequireIdempotencyKey
            public Map<String, Object> pay(@RequestBody Map<String, Object> body) {
                return Map.of("ok", true);
            }
        }
    }
}
