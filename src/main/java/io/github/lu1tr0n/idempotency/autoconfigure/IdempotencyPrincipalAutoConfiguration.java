package io.github.lu1tr0n.idempotency.autoconfigure;

import io.github.lu1tr0n.idempotency.principal.IdempotencyPrincipalResolver;
import io.github.lu1tr0n.idempotency.principal.ReactiveIdempotencyPrincipalResolver;
import io.github.lu1tr0n.idempotency.principal.spring.ReactiveSpringSecurityPrincipalResolver;
import io.github.lu1tr0n.idempotency.principal.spring.SpringSecurityPrincipalResolver;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the default Spring Security-backed principal resolvers used by
 * {@code principal-binding}. Each bean is gated on the corresponding Spring
 * Security type being on the classpath and on the consumer not having supplied
 * their own resolver, so:
 *
 * <ul>
 *   <li>No Spring Security on the classpath → no resolver bean → {@code auto}
 *       degrades to the bare key (behaviour unchanged); {@code required} fails
 *       fast where it is wired (see {@code IdempotencyAutoConfiguration}).</li>
 *   <li>A user-defined {@link IdempotencyPrincipalResolver} /
 *       {@link ReactiveIdempotencyPrincipalResolver} bean wins via
 *       {@link ConditionalOnMissingBean} — e.g. principal from a JWT claim or
 *       API-key id without Spring Security.</li>
 * </ul>
 *
 * <p>Runs before the servlet / reactive / AOP auto-configs so the resolver is
 * available when they wire the scoping layer.
 */
@AutoConfiguration(before = {
    IdempotencyAutoConfiguration.class,
    IdempotencyReactiveAutoConfiguration.class,
    IdempotencyAopAutoConfiguration.class
})
public class IdempotencyPrincipalAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    public IdempotencyPrincipalResolver springSecurityPrincipalResolver() {
        return new SpringSecurityPrincipalResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.security.core.context.ReactiveSecurityContextHolder")
    public ReactiveIdempotencyPrincipalResolver reactiveSpringSecurityPrincipalResolver() {
        return new ReactiveSpringSecurityPrincipalResolver();
    }
}
