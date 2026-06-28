package io.github.lu1tr0n.idempotency.autoconfigure;

import io.github.lu1tr0n.idempotency.principal.IdempotencyPrincipalResolver;
import io.github.lu1tr0n.idempotency.principal.ReactiveIdempotencyPrincipalResolver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classpath gating of the default Spring Security principal resolvers. Pins the
 * fix for a latent bug where {@code reactiveSpringSecurityPrincipalResolver} was
 * guarded only by {@code @ConditionalOnClass(ReactiveSecurityContextHolder)} —
 * but that type ships inside <em>spring-security-core</em>, present in a plain
 * servlet app that pulls Spring Security but no WebFlux — so the bean was
 * created there and crashed context refresh with a {@code NoClassDefFoundError}
 * on reactor's {@code Mono} (the resolver's interface returns {@code Mono}).
 *
 * <p>The starter's other tests cannot catch this: their classpath carries
 * reactor (via spring-boot-starter-webflux), so the reactive resolver
 * instantiates harmlessly. The {@link FilteredClassLoader} case removes reactor
 * to reproduce the real servlet-only-with-security deployment (the scenario the
 * PetClinic REST sample boots into).
 */
class PrincipalResolverAutoConfigurationGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(IdempotencyPrincipalAutoConfiguration.class));

    @Test
    void securityPresent_reactorAbsent_reactiveResolverBacksOff_contextStillBoots() {
        runner
            .withClassLoader(new FilteredClassLoader(Mono.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                // Servlet resolver still wired (SecurityContextHolder needs no reactor)...
                assertThat(context).hasSingleBean(IdempotencyPrincipalResolver.class);
                // ...but the reactive one must back off, not crash the refresh.
                assertThat(context).doesNotHaveBean(ReactiveIdempotencyPrincipalResolver.class);
            });
    }

    @Test
    void securityAndReactorPresent_bothResolversWired() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdempotencyPrincipalResolver.class);
            assertThat(context).hasSingleBean(ReactiveIdempotencyPrincipalResolver.class);
        });
    }
}
