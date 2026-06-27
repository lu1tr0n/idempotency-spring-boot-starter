package io.github.lu1tr0n.idempotency.autoconfigure;

import io.github.lu1tr0n.idempotency.core.HeaderIdempotencyKeyResolver;
import io.github.lu1tr0n.idempotency.core.IdempotencyKeyResolver;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.principal.IdempotencyPrincipalResolver;
import io.github.lu1tr0n.idempotency.principal.PrincipalScopingKeyResolver;
import io.github.lu1tr0n.idempotency.servlet.IdempotencyFilter;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAutoConfiguration.class);

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
                                               ObjectProvider<IdempotencyPrincipalResolver> principalProvider,
                                               IdempotencyProperties properties) {
        IdempotencyKeyResolver resolver = resolverProvider.getIfAvailable(
            () -> new HeaderIdempotencyKeyResolver(properties.getHeaderName()));
        resolver = applyPrincipalBinding(resolver, principalProvider, properties);
        return new IdempotencyFilter(store, resolver, properties);
    }

    /**
     * Wraps the key resolver so the stored key is scoped to the authenticated
     * principal, unless binding is {@code disabled}. With {@code required} and
     * no resolver available, fail fast at startup rather than 422-ing every
     * request — a missing resolver there is a deployment mistake.
     */
    private static IdempotencyKeyResolver applyPrincipalBinding(
            IdempotencyKeyResolver resolver,
            ObjectProvider<IdempotencyPrincipalResolver> principalProvider,
            IdempotencyProperties properties) {
        IdempotencyProperties.PrincipalBinding binding = properties.getPrincipalBinding();
        if (binding == IdempotencyProperties.PrincipalBinding.DISABLED) {
            return resolver;
        }
        IdempotencyPrincipalResolver principalResolver = principalProvider.getIfAvailable();
        if (principalResolver == null) {
            if (binding == IdempotencyProperties.PrincipalBinding.REQUIRED) {
                throw new IllegalStateException(
                    "spring.idempotency.principal-binding=required but no IdempotencyPrincipalResolver "
                        + "bean is available. Put Spring Security on the classpath or define your own "
                        + "IdempotencyPrincipalResolver bean.");
            }
            // auto + no resolver → keys are NOT principal-scoped. Fail loud so a
            // deployment that expected isolation isn't silently unprotected.
            log.warn("spring.idempotency.principal-binding=auto but no IdempotencyPrincipalResolver bean "
                + "is available; idempotency keys are NOT scoped to the principal. Put Spring Security on "
                + "the classpath or define your own IdempotencyPrincipalResolver bean to enable scoping.");
            return resolver;
        }
        return new PrincipalScopingKeyResolver(
            resolver, principalResolver,
            binding == IdempotencyProperties.PrincipalBinding.REQUIRED);
    }
}
