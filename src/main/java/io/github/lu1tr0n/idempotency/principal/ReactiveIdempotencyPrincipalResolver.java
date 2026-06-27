package io.github.lu1tr0n.idempotency.principal;

import reactor.core.publisher.Mono;

/**
 * Reactive counterpart of {@link IdempotencyPrincipalResolver} for the WebFlux
 * stack, where the principal lives in the Reactor context rather than a
 * thread-local and must be obtained without blocking.
 *
 * <p>The default implementation reads Spring Security's
 * {@code ReactiveSecurityContextHolder}. Supply your own {@code @Bean} to source
 * the principal differently.
 *
 * <p>Return an empty {@link Mono} for an anonymous request (yields the bare key
 * in {@code auto} mode, a 422 in {@code required} mode). Must never block.
 */
@FunctionalInterface
public interface ReactiveIdempotencyPrincipalResolver {

    /**
     * @return a {@code Mono} emitting a stable principal identifier, or an empty
     *         {@code Mono} when the request is anonymous. Must not emit blank.
     */
    Mono<String> resolvePrincipal();
}
