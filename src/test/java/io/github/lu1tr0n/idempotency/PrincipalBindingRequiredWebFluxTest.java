package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import java.util.Map;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Reactive {@code principal-binding=required}: a keyed request with no
 * authenticated principal is refused 422 (the security control that prevents a
 * cross-principal replay), while an authenticated one proceeds. Reactive parity
 * for {@code PrincipalBindingRequiredServletTest}.
 */
@SpringBootTest(
    classes = PrincipalBindingRequiredWebFluxTest.TestApp.class,
    properties = {
        "spring.main.web-application-type=reactive",
        "spring.idempotency.principal-binding=required"
    })
class PrincipalBindingRequiredWebFluxTest {

    @Autowired
    ApplicationContext context;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context)
            .apply(springSecurity())
            .configureClient()
            .build();
    }

    @Test
    void anonymousKeyedRequest_isRejectedWith422() {
        client.post().uri("/payments")
            .header("Idempotency-Key", "k-1")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"amount\":1}")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
            .expectBody().jsonPath("$.error.code").isEqualTo("IDEMPOTENCY_PRINCIPAL_REQUIRED");
    }

    @Test
    void authenticatedKeyedRequest_proceeds() {
        client.mutateWith(mockUser("alice")).post().uri("/payments")
            .header("Idempotency-Key", "k-2")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"amount\":1}")
            .exchange()
            .expectStatus().isOk();
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
        static class PaymentController {
            @PostMapping("/payments")
            public Mono<Map<String, Object>> pay(@RequestBody Map<String, Object> body) {
                return Mono.just(Map.of("ok", true));
            }
        }
    }
}
