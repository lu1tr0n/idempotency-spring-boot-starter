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
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code principal-binding=required}: a keyed request with no authenticated
 * principal is refused with 422, while an authenticated one proceeds.
 */
@SpringBootTest(
    classes = PrincipalBindingRequiredServletTest.TestApp.class,
    properties = "spring.idempotency.principal-binding=required")
@AutoConfigureMockMvc
class PrincipalBindingRequiredServletTest {

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousKeyedRequest_isRejectedWith422() throws Exception {
        mvc.perform(post("/payments")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("IDEMPOTENCY_PRINCIPAL_REQUIRED")));
    }

    @Test
    void authenticatedKeyedRequest_proceeds() throws Exception {
        mvc.perform(post("/payments").with(user("alice"))
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
            .andExpect(status().isOk());
    }

    @SpringBootApplication
    static class TestApp {
        @Bean
        IdempotencyStore store() {
            return new InMemoryIdempotencyStore();
        }

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll());
            return http.build();
        }

        @RestController
        static class PaymentController {
            @PostMapping("/payments")
            public Map<String, Object> pay(@RequestBody Map<String, Object> body) {
                return Map.of("ok", true);
            }
        }
    }
}
