package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * IETF §5 mitigation on the WebFlux stack. This is also the gate for the
 * filter-ordering fix: principal binding only works if the filter runs after
 * Spring Security has populated the reactive {@code SecurityContext}, so a
 * passing cross-principal test proves the reordering.
 */
@SpringBootTest(
    classes = PrincipalBindingWebFluxIntegrationTest.TestApp.class,
    properties = {
        "spring.idempotency.enabled=true",
        "spring.main.web-application-type=reactive"
    }
)
class PrincipalBindingWebFluxIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    InMemoryIdempotencyStore store;

    @Autowired
    TestApp.ReactivePaymentController controller;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context)
            .apply(springSecurity())
            .configureClient()
            .build();
    }

    @AfterEach
    void reset() {
        store.clear();
        controller.callCount.set(0);
    }

    @Test
    void differentPrincipals_sameKey_doNotReplayAcrossUsers() {
        client.mutateWith(mockUser("alice")).post().uri("/payments")
            .header("Idempotency-Key", "shared-key")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"amount\":1000}")
            .exchange().expectStatus().isOk()
            .expectHeader().doesNotExist("Idempotency-Replayed");
        assertThat(controller.callCount.get()).isEqualTo(1);

        client.mutateWith(mockUser("bob")).post().uri("/payments")
            .header("Idempotency-Key", "shared-key")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"amount\":1000}")
            .exchange().expectStatus().isOk()
            .expectHeader().doesNotExist("Idempotency-Replayed");
        // Bob must not replay Alice's response: proves the principal was read
        // from the reactive SecurityContext (filter runs after security).
        assertThat(controller.callCount.get()).isEqualTo(2);
    }

    @Test
    void samePrincipal_sameKey_replays() {
        client.mutateWith(mockUser("alice")).post().uri("/payments")
            .header("Idempotency-Key", "alice-key")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"amount\":5}")
            .exchange().expectStatus().isOk();
        assertThat(controller.callCount.get()).isEqualTo(1);

        client.mutateWith(mockUser("alice")).post().uri("/payments")
            .header("Idempotency-Key", "alice-key")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"amount\":5}")
            .exchange().expectStatus().isOk()
            .expectHeader().valueEquals("Idempotency-Replayed", "true");
        assertThat(controller.callCount.get()).isEqualTo(1);
    }

    @Test
    void anonymousRequest_storesUnderAnonymousNamespace() {
        client.post().uri("/payments")
            .header("Idempotency-Key", "anon-key")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"amount\":9}")
            .exchange().expectStatus().isOk();
        assertThat(store.findRecord(IdempotencyKey.of("a:anon-key"))).isPresent();
        assertThat(store.findRecord(IdempotencyKey.of("anon-key"))).isEmpty();
    }

    @SpringBootApplication
    static class TestApp {

        @Bean
        IdempotencyStore store() {
            return new InMemoryIdempotencyStore();
        }

        @Bean
        SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
            return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().permitAll())
                .build();
        }

        @RestController
        static class ReactivePaymentController {
            final AtomicInteger callCount = new AtomicInteger();

            @PostMapping("/payments")
            public Mono<Map<String, Object>> pay(@RequestBody Map<String, Object> body) {
                return Mono.fromSupplier(
                    () -> Map.of("ok", true, "callNumber", callCount.incrementAndGet(), "echo", body));
            }
        }
    }
}
