package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code max-body-size=-1} disables the cap: a large keyed body is buffered and
 * tracked as before (opt-out / backwards-compatible path).
 */
@SpringBootTest(
    classes = BodySizeCapDisabledServletTest.TestApp.class,
    properties = {
        "spring.idempotency.max-body-size=-1",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
            + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
    })
@AutoConfigureMockMvc
class BodySizeCapDisabledServletTest {

    @Autowired
    MockMvc mvc;

    @Test
    void largeKeyedBody_proceedsWhenCapDisabled() throws Exception {
        String big = "{\"data\":\"" + "x".repeat(200_000) + "\"}";
        mvc.perform(post("/echo")
                .header("Idempotency-Key", "k-big")
                .contentType(MediaType.APPLICATION_JSON).content(big))
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
