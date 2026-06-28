package io.github.lu1tr0n.idempotency.autoconfigure;

import io.github.lu1tr0n.idempotency.core.HeaderIdempotencyKeyResolver;
import io.github.lu1tr0n.idempotency.core.IdempotencyKeyResolver;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.health.IdempotencyStoreHealthIndicator;
import io.github.lu1tr0n.idempotency.observability.IdempotencyObservations;
import io.github.lu1tr0n.idempotency.principal.IdempotencyPrincipalResolver;
import io.github.lu1tr0n.idempotency.principal.PrincipalScopingKeyResolver;
import io.github.lu1tr0n.idempotency.servlet.IdempotencyFilter;
import io.github.lu1tr0n.idempotency.servlet.RequireIdempotencyKeyInterceptor;

import io.micrometer.observation.ObservationRegistry;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.HealthIndicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
                                               ObjectProvider<IdempotencyObservations> observationsProvider,
                                               IdempotencyProperties properties) {
        IdempotencyKeyResolver resolver = resolverProvider.getIfAvailable(
            () -> new HeaderIdempotencyKeyResolver(properties.getHeaderName()));
        resolver = applyPrincipalBinding(resolver, principalProvider, properties);
        IdempotencyObservations observations = observationsProvider.getIfAvailable(IdempotencyObservations::noop);
        return new IdempotencyFilter(store, resolver, properties, observations);
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

    /**
     * Registers the {@code HandlerInterceptor} that enforces
     * {@link io.github.lu1tr0n.idempotency.annotation.RequireIdempotencyKey} on
     * Spring MVC endpoints. Independent of the store — the interceptor only
     * needs the configured header name — so it activates whenever Spring MVC is
     * present, even in apps that wire their own store. The interceptor no-ops
     * unless the resolved handler carries the annotation, so registering it on
     * {@code /**} is cheap.
     *
     * <p>Kept as a nested {@code @Configuration} so the {@code @ConditionalOn*}
     * surface stays in this one file, per the package convention.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(WebMvcConfigurer.class)
    static class RequireIdempotencyKeyWebMvcConfiguration implements WebMvcConfigurer {

        private final RequireIdempotencyKeyInterceptor interceptor;

        RequireIdempotencyKeyWebMvcConfiguration(RequireIdempotencyKeyInterceptor interceptor) {
            this.interceptor = interceptor;
        }

        /**
         * Exposed as an overridable bean — same convention as
         * {@code idempotencyKeyResolver} / {@code idempotencyFilter} — so a user
         * can replace the enforcement (e.g. a different missing-key status) by
         * defining their own {@link RequireIdempotencyKeyInterceptor} bean.
         */
        @Bean
        @ConditionalOnMissingBean
        static RequireIdempotencyKeyInterceptor requireIdempotencyKeyInterceptor(IdempotencyProperties properties) {
            return new RequireIdempotencyKeyInterceptor(properties);
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(interceptor).addPathPatterns("/**");
        }
    }

    /**
     * Wires the {@link IdempotencyObservations} collaborator when the Micrometer
     * Observation API is on the classpath (it ships transitively with
     * spring-web). The {@link ObservationRegistry} bean only exists when the
     * consumer has Actuator / a tracer; absent that, we fall back to
     * {@link ObservationRegistry#NOOP} so instrumentation is a free no-op.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ObservationRegistry.class)
    @ConditionalOnProperty(prefix = "spring.idempotency.observations", name = "enabled",
        havingValue = "true", matchIfMissing = true)
    static class IdempotencyObservationsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        IdempotencyObservations idempotencyObservations(ObjectProvider<ObservationRegistry> registryProvider,
                                                        IdempotencyProperties properties) {
            ObservationRegistry registry = registryProvider.getIfAvailable(() -> ObservationRegistry.NOOP);
            return new IdempotencyObservations(registry, properties.getObservations().isEnabled());
        }
    }

    /**
     * Registers the {@code idempotency} Actuator health indicator when Spring
     * Boot Actuator is on the classpath and the consumer has not disabled it via
     * {@code management.health.idempotency.enabled=false}.
     *
     * <p>The store is resolved through an {@link ObjectProvider} rather than a
     * {@code @ConditionalOnBean(IdempotencyStore.class)}. {@code idempotencyFilter}
     * above can use {@code @ConditionalOnBean} because it is a {@code @Bean} on the
     * outer {@code @AutoConfiguration}, which carries
     * {@code after = {…StoreAutoConfiguration}} — so the store bean definition is
     * guaranteed present when its condition runs. This indicator lives in a plain
     * nested {@code @Configuration} that does not inherit that ordering, so a
     * {@code @ConditionalOnBean} here evaluates before the store auto-configs have
     * contributed their definitions and would consistently miss the store. The
     * provider sidesteps the ordering entirely: it resolves lazily at
     * bean-creation time, after the full definition graph is known — yielding
     * {@code null} (no indicator) only when there genuinely is no store to probe.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnEnabledHealthIndicator("idempotency")
    static class IdempotencyHealthConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "idempotencyHealthIndicator")
        IdempotencyStoreHealthIndicator idempotencyHealthIndicator(ObjectProvider<IdempotencyStore> storeProvider,
                                                                   IdempotencyProperties properties) {
            IdempotencyStore store = storeProvider.getIfAvailable();
            if (store == null) {
                return null; // no store configured → nothing to probe, no indicator
            }
            return new IdempotencyStoreHealthIndicator(store, properties);
        }
    }
}
