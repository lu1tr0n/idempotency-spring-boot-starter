package io.github.lu1tr0n.idempotency.health;

import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyAutoConfiguration;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conditional wiring of the {@code idempotency} health indicator, exercised with
 * {@link ApplicationContextRunner} (no container, no web environment). Pins the
 * branches that are easy to regress: the indicator must back off cleanly when
 * there is no store to probe, and the standard Actuator switch must disable it.
 */
class HealthIndicatorAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class));

    @Test
    void noStore_indicatorBacksOff() {
        // No IdempotencyStore bean (and no DataSource/Redis to auto-create one):
        // the ObjectProvider resolves to null and the @Bean returns null. A null
        // @Bean still registers a definition by its return type, so the *name*
        // exists — but it resolves to a NullBean, which an ObjectProvider (and
        // therefore Actuator's contributor collection) skips. Assert the
        // resolved instance is absent, the contract that actually matters.
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanProvider(IdempotencyStoreHealthIndicator.class).getIfAvailable())
                .as("no usable health indicator when there is no store")
                .isNull();
        });
    }

    @Test
    void withStore_indicatorRegistered() {
        runner.withBean(IdempotencyStore.class, InMemoryIdempotencyStore::new)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(IdempotencyStoreHealthIndicator.class);
            });
    }

    @Test
    void disabledViaProperty_indicatorBacksOff() {
        runner.withBean(IdempotencyStore.class, InMemoryIdempotencyStore::new)
            .withPropertyValues("management.health.idempotency.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(IdempotencyStoreHealthIndicator.class);
            });
    }
}
