package io.github.lu1tr0n.idempotency.principal;

import java.util.Optional;

/**
 * Resolves the authenticated principal for the current (servlet-stack) request,
 * used to scope the idempotency key so one principal cannot replay another's
 * cached response (IETF Idempotency-Key draft §5).
 *
 * <p>The default implementation reads Spring Security's
 * {@code SecurityContextHolder}. Provide your own {@code @Bean} to source the
 * principal from elsewhere — a JWT claim, an API-key id, a tenant header — when
 * you don't use Spring Security or want a different identity than
 * {@code Authentication#getName()}.
 *
 * <p>Return {@link Optional#empty()} for an anonymous / unauthenticated request:
 * in {@code auto} mode that yields an anonymous-namespaced key, and in
 * {@code required} mode it triggers a 422.
 *
 * <p><strong>Identity must be unique and non-reassignable.</strong> The returned
 * value scopes the cache, so two distinct identities mapping to the same string
 * would let one replay the other's response. Prefer an immutable subject id
 * (e.g. the JWT {@code sub} claim or a database user id) over a display name or
 * a reusable email. The default implementation uses
 * {@code Authentication#getName()}, which is appropriate when that value is a
 * stable, unique subject identifier for your provider.
 *
 * <p>This is the blocking, servlet/AOP variant. The reactive stack uses
 * {@link ReactiveIdempotencyPrincipalResolver} so that no {@code reactor-core}
 * type leaks onto a servlet-only consumer's classpath.
 */
@FunctionalInterface
public interface IdempotencyPrincipalResolver {

    /**
     * @return a stable identifier for the current principal, or empty when the
     *         request is anonymous / unauthenticated. Must not return a blank
     *         string — return empty instead.
     */
    Optional<String> resolvePrincipal();
}
