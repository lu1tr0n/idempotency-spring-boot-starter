package io.github.lu1tr0n.idempotency.autoconfigure;

import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.heartbeat.LockHeartbeat;
import io.github.lu1tr0n.idempotency.principal.ReactiveIdempotencyPrincipalResolver;
import io.github.lu1tr0n.idempotency.reactive.IdempotencyWebFilter;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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
 *
 * <p><strong>Activation is gated on a REACTIVE web application</strong>, not on
 * {@code @ConditionalOnClass(WebFilter.class)} alone. {@code WebFilter} lives in
 * {@code spring-web}, which is present in <em>every</em> servlet app too, so a
 * class-only guard would match a servlet-only app and then fail to instantiate
 * {@link IdempotencyWebFilter} (it references reactor, absent on a servlet
 * classpath) — {@code NoClassDefFoundError} at startup. {@code type=REACTIVE}
 * is a filtering condition resolved from the web-application type before this
 * configuration class is processed, so a servlet/non-web app backs off without
 * loading the reactor-referencing filter. Mirrors the servlet filter, which is
 * {@code SERVLET}-gated.
 */
@AutoConfiguration(after = {
    InMemoryStoreAutoConfiguration.class,
    JdbcStoreAutoConfiguration.class,
    RedisStoreAutoConfiguration.class
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(WebFilter.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyReactiveAutoConfiguration {

    @Bean
    @ConditionalOnBean(IdempotencyStore.class)
    @ConditionalOnMissingBean
    public IdempotencyWebFilter idempotencyWebFilter(
            IdempotencyStore store,
            IdempotencyProperties properties,
            ObjectProvider<ReactiveIdempotencyPrincipalResolver> principalProvider,
            ObjectProvider<LockHeartbeat> heartbeatProvider) {
        ReactiveIdempotencyPrincipalResolver principalResolver = principalProvider.getIfAvailable();
        LockHeartbeat heartbeat = heartbeatProvider.getIfAvailable(() -> LockHeartbeat.NOOP);
        if (principalResolver == null
            && properties.getPrincipalBinding() == IdempotencyProperties.PrincipalBinding.REQUIRED) {
            throw new IllegalStateException(
                "spring.idempotency.principal-binding=required but no ReactiveIdempotencyPrincipalResolver "
                    + "bean is available. Put Spring Security on the classpath or define your own "
                    + "ReactiveIdempotencyPrincipalResolver bean.");
        }
        if (principalResolver == null
            && properties.getPrincipalBinding() == IdempotencyProperties.PrincipalBinding.AUTO) {
            LoggerFactory.getLogger(IdempotencyReactiveAutoConfiguration.class).warn(
                "spring.idempotency.principal-binding=auto but no ReactiveIdempotencyPrincipalResolver bean "
                    + "is available; idempotency keys are NOT scoped to the principal. Put Spring Security on "
                    + "the classpath or define your own ReactiveIdempotencyPrincipalResolver bean.");
        }
        return new IdempotencyWebFilter(store, properties, principalResolver, heartbeat);
    }
}
