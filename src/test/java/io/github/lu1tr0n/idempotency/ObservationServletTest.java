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
 * Observation instrumentation: each idempotency outcome emits an
 * {@code idempotency} observation tagged with a low-cardinality outcome and
 * status — and never the key.
 */
@SpringBootTest(
    classes = ObservationServletTest.TestApp.class,
    properties = {
        "spring.idempotency.max-body-size=64B",
        "spring.idempotency.non-cacheable-statuses=400",
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"
    })
@AutoConfigureMockMvc
class ObservationServletTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestObservationRegistry registry;

    @Test
    void emitsOutcomeTaggedObservations_perBranch() throws Exception {
        // 1. miss → handler runs, response cached.
        mvc.perform(post("/pay")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
            .andExpect(status().isOk());
        // 2. same key + body → replay.
        mvc.perform(post("/pay")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
            .andExpect(status().isOk());
        // 3. same key, different body → mismatch 422.
        mvc.perform(post("/pay")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":999}"))
            .andExpect(status().isUnprocessableEntity());
        // 4. over-cap request body → 413.
        mvc.perform(post("/pay")
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"data\":\"" + "x".repeat(80) + "\"}"))
            .andExpect(status().isPayloadTooLarge());
        // 5. handler returns a non-cacheable 400 → executed but not stored.
        mvc.perform(post("/fail")
                .header("Idempotency-Key", "k-3")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());

        TestObservationRegistryAssert.assertThat(registry)
            .hasNumberOfObservationsWithNameEqualTo("idempotency", 5)
            .hasAnObservationWithAKeyValue("idempotency.outcome", "executed_stored")
            .hasAnObservationWithAKeyValue("idempotency.outcome", "replayed")
            .hasAnObservationWithAKeyValue("idempotency.outcome", "payload_mismatch")
            .hasAnObservationWithAKeyValue("idempotency.outcome", "payload_too_large")
            .hasAnObservationWithAKeyValue("idempotency.outcome", "executed_not_stored")
            // Only low-cardinality, non-sensitive tags — never the key (PII).
            .forAllObservationsWithNameEqualTo("idempotency", o -> o
                .hasLowCardinalityKeyValueWithKey("idempotency.status")
                .doesNotHaveLowCardinalityKeyValueWithKey("idempotency.key"));
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

            @PostMapping("/fail")
            public org.springframework.http.ResponseEntity<Map<String, Object>> fail(
                    @RequestBody Map<String, Object> body) {
                return org.springframework.http.ResponseEntity.badRequest().body(Map.of("ok", false));
            }
        }
    }
}
