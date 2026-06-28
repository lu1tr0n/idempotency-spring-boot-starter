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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @RequireIdempotencyKey}: an annotated endpoint rejects a request that
 * omits the {@code Idempotency-Key} header with 400, while a keyed request and
 * unannotated endpoints behave exactly as before.
 */
@SpringBootTest(
    classes = RequireIdempotencyKeyServletTest.TestApp.class,
    properties =
        // spring-security is on the test classpath for the principal-binding
        // tests; exclude its auto-config here so these endpoints stay unsecured
        // and the test exercises the missing-key 400, not a 403.
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
            + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration")
@AutoConfigureMockMvc
class RequireIdempotencyKeyServletTest {

    @Autowired
    MockMvc mvc;

    @Test
    void missingKeyOnAnnotatedMethod_isRejectedWith400() throws Exception {
        mvc.perform(post("/pay")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(Matchers.containsString("IDEMPOTENCY_KEY_REQUIRED")))
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void blankKeyOnAnnotatedMethod_isRejectedWith400() throws Exception {
        mvc.perform(post("/pay")
                .header("Idempotency-Key", "   ")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(Matchers.containsString("IDEMPOTENCY_KEY_REQUIRED")));
    }

    @Test
    void presentKeyOnAnnotatedMethod_proceeds() throws Exception {
        mvc.perform(post("/pay")
                .header("Idempotency-Key", "k-pay-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("ok")));
    }

    @Test
    void classLevelAnnotation_appliesToEveryHandler() throws Exception {
        // No method-level annotation on /transfers/create — it inherits the
        // requirement from the @RequireIdempotencyKey on the controller class.
        mvc.perform(post("/transfers/create")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(Matchers.containsString("IDEMPOTENCY_KEY_REQUIRED")));

        mvc.perform(post("/transfers/create")
                .header("Idempotency-Key", "k-transfer-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"x\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void unannotatedEndpoint_keepsOptInBehaviour() throws Exception {
        // Default semantics preserved: a tracked method WITHOUT the annotation
        // still proceeds untracked when no key is supplied.
        mvc.perform(post("/notes")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hi\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void malformedPresentKey_isRejectedByFilterNotInterceptor() throws Exception {
        // Composition boundary: a present-but-malformed key is rejected by the
        // filter (INVALID_IDEMPOTENCY_KEY) before the dispatcher runs, so the
        // interceptor's required-key check never fires.
        mvc.perform(post("/pay")
                .header("Idempotency-Key", "has spaces!")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(Matchers.containsString("INVALID_IDEMPOTENCY_KEY")));
    }

    @Test
    void inheritedHandlerMethod_requirementIsPerConcreteController() throws Exception {
        // Regression: /secure-base and /open-base resolve to the SAME inherited
        // Method on BaseOpController, but only SecureOpController is annotated.
        // Hit the unannotated sibling first to poison any method-only cache,
        // then the annotated one must STILL reject a keyless request (no silent
        // fail-open).
        mvc.perform(post("/open-base")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk());

        mvc.perform(post("/secure-base")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(Matchers.containsString("IDEMPOTENCY_KEY_REQUIRED")));

        mvc.perform(post("/secure-base")
                .header("Idempotency-Key", "k-base-1")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
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

            @PostMapping("/notes")
            public Map<String, Object> note(@RequestBody Map<String, Object> body) {
                return Map.of("ok", true);
            }
        }

        @RestController
        @RequireIdempotencyKey
        static class TransferController {
            @PostMapping("/transfers/create")
            public Map<String, Object> create(@RequestBody Map<String, Object> body) {
                return Map.of("ok", true);
            }
        }

        // Two controllers sharing one inherited handler Method, differing only
        // in the class-level @RequireIdempotencyKey — the cache-collision case.
        abstract static class BaseOpController {
            @PostMapping
            public Map<String, Object> op(@RequestBody Map<String, Object> body) {
                return Map.of("ok", true);
            }
        }

        @RestController
        @RequestMapping("/secure-base")
        @RequireIdempotencyKey
        static class SecureOpController extends BaseOpController {
        }

        @RestController
        @RequestMapping("/open-base")
        static class OpenOpController extends BaseOpController {
        }
    }
}
