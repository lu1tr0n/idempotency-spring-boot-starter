package io.github.lu1tr0n.idempotency.principal;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyKeyResolver;
import io.github.lu1tr0n.idempotency.exception.IdempotencyPrincipalRequiredException;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * Servlet-stack decorator that scopes the key produced by a delegate resolver to
 * the authenticated principal. Installed only when
 * {@code spring.idempotency.principal-binding} is not {@code disabled} and a
 * {@link IdempotencyPrincipalResolver} bean is present, so the base
 * {@code IdempotencyFilter} stays unaware of principal binding.
 *
 * <p>Wrapping the delegate (rather than the filter) means a user who replaces
 * the key resolver — e.g. a tenant-prefixing one — still gets principal scoping
 * layered on top of their key.
 */
public final class PrincipalScopingKeyResolver implements IdempotencyKeyResolver {

    private final IdempotencyKeyResolver delegate;
    private final IdempotencyPrincipalResolver principalResolver;
    private final boolean required;

    public PrincipalScopingKeyResolver(IdempotencyKeyResolver delegate,
                                       IdempotencyPrincipalResolver principalResolver,
                                       boolean required) {
        this.delegate = delegate;
        this.principalResolver = principalResolver;
        this.required = required;
    }

    @Override
    public Optional<IdempotencyKey> resolve(HttpServletRequest request) {
        Optional<IdempotencyKey> rawKey = delegate.resolve(request);
        if (rawKey.isEmpty()) {
            // No key header → untracked, exactly as before. Binding never forces
            // a key to be present; it only scopes one that is.
            return rawKey;
        }
        Optional<String> principal = principalResolver.resolvePrincipal();
        if (principal.isEmpty()) {
            if (required) {
                throw new IdempotencyPrincipalRequiredException(
                    "principal-binding=required but the request supplied an Idempotency-Key "
                        + "without an authenticated principal to scope it to.");
            }
            // auto + anonymous → anonymous namespace, kept disjoint from the
            // scoped namespace so it cannot be forged into a victim's key.
            return Optional.of(PrincipalKeyComposer.anonymous(rawKey.get()));
        }
        return Optional.of(PrincipalKeyComposer.compose(principal.get(), rawKey.get()));
    }
}
