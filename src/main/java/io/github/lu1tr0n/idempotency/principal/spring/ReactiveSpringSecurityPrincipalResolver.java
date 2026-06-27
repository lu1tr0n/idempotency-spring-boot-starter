package io.github.lu1tr0n.idempotency.principal.spring;

import io.github.lu1tr0n.idempotency.principal.ReactiveIdempotencyPrincipalResolver;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;

import reactor.core.publisher.Mono;

/**
 * Default WebFlux principal resolver, reading the {@code Authentication} from
 * Spring Security's {@code ReactiveSecurityContextHolder} (the Reactor-context
 * security context). Emits an empty {@code Mono} for absent / unauthenticated /
 * anonymous requests so {@code auto} mode falls back to the bare key.
 */
public final class ReactiveSpringSecurityPrincipalResolver implements ReactiveIdempotencyPrincipalResolver {

    @Override
    public Mono<String> resolvePrincipal() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(authentication -> authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken))
            .map(Authentication::getName)
            .filter(name -> name != null && !name.isBlank());
    }
}
