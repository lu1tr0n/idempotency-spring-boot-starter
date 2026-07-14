package io.github.lu1tr0n.idempotency.servlet;

import jakarta.servlet.http.HttpServletRequest;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;

import java.util.Optional;

/**
 * Strategy for extracting the idempotency key from an incoming HTTP request.
 *
 * <p>The default implementation reads a configurable header (defaults to
 * {@code Idempotency-Key}, matching the Stripe convention). Apps with
 * non-trivial requirements — e.g. composing the key with the authenticated
 * tenant ID so two tenants cannot collide — replace the bean:
 *
 * <pre>{@code
 * @Bean
 * IdempotencyKeyResolver tenantScopedResolver(TenantContext tenants) {
 *     return request -> {
 *         String raw = request.getHeader("Idempotency-Key");
 *         if (raw == null || raw.isBlank()) return Optional.empty();
 *         return Optional.of(IdempotencyKey.of(tenants.currentTenantId() + ":" + raw));
 *     };
 * }
 * }</pre>
 *
 * <p>Returning an empty {@link Optional} means "no key supplied" — the request
 * proceeds without idempotency tracking. Throwing
 * {@link IllegalArgumentException} from {@link IdempotencyKey#of(String)} means
 * "key supplied but malformed" — the filter returns 400.
 */
@FunctionalInterface
public interface IdempotencyKeyResolver {

    Optional<IdempotencyKey> resolve(HttpServletRequest request);
}
