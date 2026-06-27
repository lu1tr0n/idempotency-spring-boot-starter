package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;

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
 * {@code spring.idempotency.observations.enabled=false} hard-disables
 * instrumentation even when a registry is present.
 */
@SpringBootTest(
    classes = ObservationDisabledServletTest.TestApp.class,
    properties = {
        "spring.idempotency.observations.enabled=false",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"
    })
@AutoConfigureMockMvc
class ObservationDisabledServletTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestObservationRegistry registry;

    @Test
    void noObservationsRecorded_whenDisabled() throws Exception {
        mvc.perform(post("/pay")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
            .andExpect(status().isOk());

        TestObservationRegistryAssert.assertThat(registry)
            .hasNumberOfObservationsWithNameEqualTo("idempotency", 0);
    }

    @SpringBootApplication
    static class TestApp {
        @Bean
        InMemoryIdempotencyStore store() {
            return new InMemoryIdempotencyStore();
        }

        @Bean
        TestObservationRegistry observationRegistry() {
            return TestObservationRegistry.create();
        }

        @RestController
        static class PaymentController {
            @PostMapping("/pay")
            public Map<String, Object> pay(@RequestBody Map<String, Object> body) {
                return Map.of("ok", true);
            }
        }
    }
}
