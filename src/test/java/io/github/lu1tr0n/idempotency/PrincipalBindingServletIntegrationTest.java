package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.principal.PrincipalKeyComposer;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IETF Idempotency-Key draft §5 data-leak mitigation, servlet stack: with
 * {@code principal-binding=auto} (the default) two principals reusing the same
 * raw key get independent records, an authenticated retry replays, and an
 * anonymous request stores the bare key unchanged.
 */
@SpringBootTest(classes = PrincipalBindingServletIntegrationTest.TestApp.class)
@AutoConfigureMockMvc
class PrincipalBindingServletIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InMemoryIdempotencyStore store;

    @Autowired
    TestApp.PaymentController controller;

    @AfterEach
    void reset() {
        store.clear();
        controller.callCount.set(0);
    }

    @Test
    void differentPrincipals_sameKey_doNotReplayAcrossUsers() throws Exception {
        // Alice creates a record under key K.
        mvc.perform(post("/payments").with(user("alice"))
                .header("Idempotency-Key", "shared-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Idempotency-Replayed"));
        assertThat(controller.callCount.get()).isEqualTo(1);

        // Bob reuses the SAME key + body. He must NOT see Alice's response —
        // his request runs fresh against the controller.
        mvc.perform(post("/payments").with(user("bob"))
                .header("Idempotency-Key", "shared-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Idempotency-Replayed"));
        assertThat(controller.callCount.get()).isEqualTo(2);
    }

    @Test
    void samePrincipal_sameKey_replays() throws Exception {
        mvc.perform(post("/payments").with(user("alice"))
                .header("Idempotency-Key", "alice-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5}"))
            .andExpect(status().isOk());
        assertThat(controller.callCount.get()).isEqualTo(1);

        mvc.perform(post("/payments").with(user("alice"))
                .header("Idempotency-Key", "alice-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Idempotency-Replayed", "true"));
        // Replayed, controller not invoked again.
        assertThat(controller.callCount.get()).isEqualTo(1);
    }

    @Test
    void authenticatedRequest_storesUnderScopedKeyNotBareKey() throws Exception {
        mvc.perform(post("/payments").with(user("alice"))
                .header("Idempotency-Key", "scoped-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":7}"))
            .andExpect(status().isOk());
        // The bare key must be absent — the record lives under phash:scoped-key.
        assertThat(store.findRecord(IdempotencyKey.of("scoped-key"))).isEmpty();
    }

    @Test
    void anonymousRequest_storesUnderAnonymousNamespace() throws Exception {
        mvc.perform(post("/payments")
                .header("Idempotency-Key", "anon-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":9}"))
            .andExpect(status().isOk());
        // auto + anonymous → a:-namespaced, disjoint from both the scoped space
        // and the raw key, so it cannot be forged into a victim's key.
        assertThat(store.findRecord(IdempotencyKey.of("a:anon-key"))).isPresent();
        assertThat(store.findRecord(IdempotencyKey.of("anon-key"))).isEmpty();
    }

    @Test
    void anonymousCannotForgeScopedKeyToReplayVictim() throws Exception {
        // Alice (authenticated) stores a record under her scoped key.
        mvc.perform(post("/payments").with(user("alice"))
                .header("Idempotency-Key", "victim-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
            .andExpect(status().isOk());
        assertThat(controller.callCount.get()).isEqualTo(1);

        // The exact scoped-key string an attacker would need to hit Alice's row.
        String forged = PrincipalKeyComposer.compose("alice", IdempotencyKey.of("victim-key")).value();

        // Anonymous attacker submits that string as a bare Idempotency-Key. It
        // must NOT replay Alice's response — IETF §5 regression for the forgeable
        // scoped/bare keyspace overlap.
        mvc.perform(post("/payments")
                .header("Idempotency-Key", forged)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Idempotency-Replayed"));
        assertThat(controller.callCount.get()).isEqualTo(2);
    }

    @SpringBootApplication
    static class TestApp {

        @Bean
        IdempotencyStore store() {
            return new InMemoryIdempotencyStore();
        }

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            // Permit everything so anonymous requests aren't 401'd; the tests
            // attach an authenticated principal explicitly via with(user(...)).
            http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll());
            return http.build();
        }

        @RestController
        static class PaymentController {
            final AtomicInteger callCount = new AtomicInteger();

            @PostMapping("/payments")
            public Map<String, Object> pay(@RequestBody Map<String, Object> body) {
                return Map.of("ok", true, "callNumber", callCount.incrementAndGet(), "echo", body);
            }
        }
    }
}
