package io.github.lu1tr0n.idempotency.principal.spring;

import io.github.lu1tr0n.idempotency.principal.IdempotencyPrincipalResolver;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Default servlet-stack principal resolver, reading the current
 * {@code Authentication} from Spring Security's thread-local
 * {@code SecurityContextHolder}. Wired only when Spring Security is on the
 * classpath and the consumer has not supplied their own
 * {@link IdempotencyPrincipalResolver}.
 *
 * <p>An absent, unauthenticated, or anonymous authentication resolves to
 * {@link Optional#empty()} — i.e. "no principal", so {@code auto} mode falls
 * back to the bare key.
 */
public final class SpringSecurityPrincipalResolver implements IdempotencyPrincipalResolver {

    @Override
    public Optional<String> resolvePrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
            || !authentication.isAuthenticated()
            || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        String name = authentication.getName();
        return (name == null || name.isBlank()) ? Optional.empty() : Optional.of(name);
    }
}
