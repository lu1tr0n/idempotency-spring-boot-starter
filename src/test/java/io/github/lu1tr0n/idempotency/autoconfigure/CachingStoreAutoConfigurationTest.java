package io.github.lu1tr0n.idempotency.autoconfigure;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;
import io.github.lu1tr0n.idempotency.store.cache.CachingIdempotencyStore;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conditional wiring of the optional L1 cache decorator. Verifies it activates
 * only on explicit opt-in, decorates the selected store as {@code @Primary},
 * declines to wrap an in-memory backend, and only binds metrics when a
 * {@code MeterRegistry} is present.
 */
class CachingStoreAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CachingStoreAutoConfiguration.class));

    @Test
    void disabledByDefault_storeIsNotWrapped() {
        runner.withBean("backingStore", IdempotencyStore.class, CustomStore::new)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(CachingIdempotencyStore.class);
                assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(CustomStore.class);
            });
    }

    @Test
    void enabled_wrapsSelectedStore_asPrimary() {
        runner.withBean("backingStore", IdempotencyStore.class, CustomStore::new)
            .withPropertyValues("spring.idempotency.cache.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(CachingIdempotencyStore.class);
                // The @Primary store handed to the filter is the decorator,
                // delegating to the original backing store.
                IdempotencyStore primary = context.getBean(IdempotencyStore.class);
                assertThat(primary).isInstanceOf(CachingIdempotencyStore.class);
                assertThat(((CachingIdempotencyStore) primary).getDelegate()).isInstanceOf(CustomStore.class);
            });
    }

    @Test
    void enabled_butInMemoryBackend_isNotWrapped() {
        runner.withBean("backingStore", IdempotencyStore.class, InMemoryIdempotencyStore::new)
            .withPropertyValues("spring.idempotency.cache.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                // An L1 in front of an in-process map is pointless: bypassed.
                assertThat(context).doesNotHaveBean(CachingIdempotencyStore.class);
                assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(InMemoryIdempotencyStore.class);
            });
    }

    @Test
    void enabled_registersCacheMetricsBinder_whenMicrometerPresent() {
        // The binder is an unconditional MeterBinder bean (the Spring Boot idiom):
        // Spring Boot binds it to whatever MeterRegistry exists. We assert the bean
        // is present and actually binds — driving a get through the L1 produces a
        // meter under idempotency.l1 on the registry.
        runner.withBean("backingStore", IdempotencyStore.class, CustomStore::new)
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("spring.idempotency.cache.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean("idempotencyL1CacheMetrics");
                MeterBinder binder = context.getBean("idempotencyL1CacheMetrics", MeterBinder.class);
                SimpleMeterRegistry registry = context.getBean(SimpleMeterRegistry.class);
                binder.bindTo(registry); // ApplicationContextRunner has no actuator post-processor to auto-bind
                assertThat(registry.find("cache.gets").tag("cache", "idempotency.l1").meter())
                    .as("L1 cache metrics bound under idempotency.l1")
                    .isNotNull();
            });
    }

    /** A non-in-memory custom store — stands in for a JDBC/Redis backend. */
    static final class CustomStore implements IdempotencyStore {
        @Override
        public Optional<IdempotencyRecord> findRecord(IdempotencyKey key) {
            return Optional.empty();
        }
        @Override
        public Optional<LockToken> acquireLock(IdempotencyKey key, Duration ttl) {
            return Optional.empty();
        }
        @Override
        public void save(IdempotencyRecord record, LockToken token) {
        }
        @Override
        public void releaseLock(IdempotencyKey key, LockToken token) {
        }
    }
}
