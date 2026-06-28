package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyAutoConfiguration;
import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyReactiveAutoConfiguration;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.reactive.IdempotencyWebFilter;
import io.github.lu1tr0n.idempotency.servlet.IdempotencyFilter;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web-type gating of the two HTTP filters. These pin the fix for a latent bug
 * where {@link IdempotencyReactiveAutoConfiguration} was guarded only by
 * {@code @ConditionalOnClass(WebFilter.class)} — but {@code WebFilter} ships in
 * spring-web, present in every servlet app — so it activated in servlet-only
 * apps and crashed at boot instantiating the reactor-based reactive filter.
 *
 * <p>The starter's other integration tests cannot catch this: their classpath
 * carries <em>both</em> spring-boot-starter-web and -webflux, so reactor is
 * always present and the mis-wired reactive filter instantiated harmlessly
 * instead of throwing. The {@link FilteredClassLoader} case below removes
 * reactor to reproduce the real servlet-only deployment.
 */
class WebFilterAutoConfigurationGuardTest {

    private static final AutoConfigurations CONFIGS = AutoConfigurations.of(
        IdempotencyAutoConfiguration.class, IdempotencyReactiveAutoConfiguration.class);

    @Test
    void servletApp_withoutReactor_bootsWithServletFilterOnly() {
        // The exact production scenario the bug crashed on: servlet web app,
        // reactor absent. The reactive auto-config must back off on the
        // web-type, never touching IdempotencyWebFilter — no NoClassDefFoundError.
        new WebApplicationContextRunner()
            .withConfiguration(CONFIGS)
            .withBean(IdempotencyStore.class, InMemoryIdempotencyStore::new)
            .withClassLoader(new FilteredClassLoader(Mono.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(IdempotencyFilter.class);
                assertThat(context).doesNotHaveBean("idempotencyWebFilter");
            });
    }

    @Test
    void servletApp_withReactorPresent_stillServletFilterOnly() {
        new WebApplicationContextRunner()
            .withConfiguration(CONFIGS)
            .withBean(IdempotencyStore.class, InMemoryIdempotencyStore::new)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(IdempotencyFilter.class);
                assertThat(context).doesNotHaveBean(IdempotencyWebFilter.class);
            });
    }

    @Test
    void reactiveApp_wiresReactiveFilterOnly() {
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(CONFIGS)
            .withBean(IdempotencyStore.class, InMemoryIdempotencyStore::new)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(IdempotencyWebFilter.class);
                assertThat(context).doesNotHaveBean(IdempotencyFilter.class);
            });
    }
}
