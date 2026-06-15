package io.github.lu1tr0n.idempotency.autoconfigure;

import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.reactive.IdempotencyWebFilter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.server.WebFilter;

/**
 * Wires {@link IdempotencyWebFilter} when Spring WebFlux is on the classpath
 * AND an {@link IdempotencyStore} is registered. Mirrors the servlet
 * {@code IdempotencyFilter} contract for reactive apps.
 *
 * <p>Loads AFTER the backend autoconfigs so the
 * {@code @ConditionalOnBean(IdempotencyStore.class)} guard finds an already
 * registered store (lab finding #6/#7).
 */
@AutoConfiguration(after = {
    InMemoryStoreAutoConfiguration.class,
    JdbcStoreAutoConfiguration.class,
    RedisStoreAutoConfiguration.class
})
@ConditionalOnClass(WebFilter.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyReactiveAutoConfiguration {

    @Bean
    @ConditionalOnBean(IdempotencyStore.class)
    @ConditionalOnMissingBean
    public IdempotencyWebFilter idempotencyWebFilter(IdempotencyStore store, IdempotencyProperties properties) {
        return new IdempotencyWebFilter(store, properties);
    }
}
