package io.github.lu1tr0n.idempotency.aop;

import java.util.Optional;

/**
 * Resolves an idempotency key from the ambient HTTP request when {@link
 * io.github.lu1tr0n.idempotency.annotation.Idempotent} omits a SpEL
 * expression.
 *
 * <p>Implementations come from auto-configuration: a servlet-stack app gets
 * a {@code RequestContextHolder}-backed resolver, a WebFlux app gets a
 * Reactor-context-backed resolver, and a plain non-web bean gets a no-op
 * resolver that always returns empty.
 *
 * <p>This indirection keeps {@code IdempotentAspect} free of Spring Web
 * imports — the aspect can run in a non-web context (background jobs,
 * scheduled tasks) and simply skip the header lookup.
 */
@FunctionalInterface
public interface IdempotentKeyResolver {

    /**
     * Return the configured header's value from the current request, or
     * {@link Optional#empty()} if there is no current request or the header
     * is absent / blank.
     */
    Optional<String> resolveFromAmbientRequest(String headerName);

    /** Resolver that always returns empty — for non-web environments. */
    IdempotentKeyResolver NO_OP = headerName -> Optional.empty();
}
