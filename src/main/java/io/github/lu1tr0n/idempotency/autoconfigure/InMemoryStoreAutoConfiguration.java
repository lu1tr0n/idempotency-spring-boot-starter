package io.github.lu1tr0n.idempotency.autoconfigure;

import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import java.util.Locale;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Registers {@link InMemoryIdempotencyStore} when
 * {@code spring.idempotency.backend=IN_MEMORY} is selected (relaxed-bound,
 * so {@code in-memory}, {@code IN_MEMORY}, {@code inMemory} all match).
 *
 * <p><strong>Not</strong> for production clusters — locks are JVM-local. Use
 * the JDBC or Redis backend if you run more than one instance.
 *
 * <p>This used to live as a user-provided bean in test code. v0.0.3 makes it
 * a real auto-config so {@code backend: in-memory} actually works out of the
 * box (see lab finding #1).
 */
@AutoConfiguration
@Conditional(InMemoryStoreAutoConfiguration.InMemoryBackendSelected.class)
public class InMemoryStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore inMemoryIdempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    /**
     * Matches when {@code spring.idempotency.backend} resolves to
     * {@code IN_MEMORY}. {@code @ConditionalOnProperty} alone cannot do the
     * relaxed-binding match (it compares raw string values).
     */
    static class InMemoryBackendSelected implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String raw = context.getEnvironment().getProperty("spring.idempotency.backend", "AUTO");
            String normalised = raw.replace('-', '_').toUpperCase(Locale.ROOT);
            return "IN_MEMORY".equals(normalised);
        }
    }
}
