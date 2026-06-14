package io.github.lu1tr0n.idempotency.autoconfigure;

import io.github.lu1tr0n.idempotency.core.HeaderIdempotencyKeyResolver;
import io.github.lu1tr0n.idempotency.core.IdempotencyKeyResolver;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.servlet.IdempotencyFilter;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level auto-configuration entry point. Activated by
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * when {@code spring.idempotency.enabled} is not explicitly set to {@code false}.
 *
 * <p>Sub-configurations live in this same package — keep them all here so
 * {@code @ConditionalOn*} discovery happens in one place and the documentation
 * surface stays small.
 */
@AutoConfiguration(after = {
    InMemoryStoreAutoConfiguration.class,
    JdbcStoreAutoConfiguration.class,
    RedisStoreAutoConfiguration.class
})
@ConditionalOnProperty(prefix = "spring.idempotency", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    /**
     * Default header-based key resolver. Replace by defining your own
     * {@link IdempotencyKeyResolver} bean — for example, to scope the key by
     * tenant or authenticated principal so two tenants cannot collide.
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyKeyResolver idempotencyKeyResolver(IdempotencyProperties props) {
        return new HeaderIdempotencyKeyResolver(props.getHeaderName());
    }

    /**
     * Installs the global servlet filter when Spring Web is on the classpath
     * AND a {@link IdempotencyStore} bean is available. The store bean comes
     * from {@link JdbcStoreAutoConfiguration}, a future Redis auto-config, or
     * user-provided code (typically {@code InMemoryIdempotencyStore} in tests).
     *
     * <p>The {@code @ConditionalOnBean} lives on the {@code @Bean} method
     * rather than on a nested {@code @Configuration} class because the latter
     * evaluates too eagerly — before user-provided beans in
     * {@code @SpringBootApplication} classes have been registered — so the
     * condition would consistently miss the store. The {@code @Bean}-level
     * variant defers evaluation until the full bean definition graph is known.
     */
    @Bean
    @ConditionalOnClass({HttpServletRequest.class, org.springframework.web.filter.OncePerRequestFilter.class})
    @ConditionalOnBean(IdempotencyStore.class)
    @ConditionalOnMissingBean
    public IdempotencyFilter idempotencyFilter(IdempotencyStore store,
                                               ObjectProvider<IdempotencyKeyResolver> resolverProvider,
                                               IdempotencyProperties properties) {
        IdempotencyKeyResolver resolver = resolverProvider.getIfAvailable(
            () -> new HeaderIdempotencyKeyResolver(properties.getHeaderName()));
        return new IdempotencyFilter(store, resolver, properties);
    }
}
