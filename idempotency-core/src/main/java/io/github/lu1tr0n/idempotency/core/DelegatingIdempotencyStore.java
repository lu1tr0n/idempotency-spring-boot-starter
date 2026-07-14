package io.github.lu1tr0n.idempotency.core;

/**
 * Implemented by any {@link IdempotencyStore} that wraps another store (a
 * decorator), exposing the store it delegates to.
 *
 * <p>The whole point is <em>capability discovery</em>. Some store capabilities
 * are advertised through a <strong>separate</strong> interface rather than the
 * {@link IdempotencyStore} SPI itself — most notably
 * {@link IdempotencyStoreHealth}, which the Actuator health indicator detects
 * with an {@code instanceof} check. A decorator (e.g. the optional L1 cache)
 * does not, and should not, blanket-implement those side interfaces: doing so
 * would falsely claim a capability the inner store may not have. Instead the
 * decorator implements this interface so collaborators can <em>unwrap</em> to
 * the innermost concrete store and probe it for the real capability.
 *
 * <p>Without this, wrapping a JDBC store (which implements
 * {@link IdempotencyStoreHealth}) in a cache decorator would silently drop the
 * health probe to {@code UNKNOWN} — a regression with no error. Unwrapping
 * restores it.
 *
 * <p>Capabilities that live on the SPI as default methods (e.g.
 * {@link IdempotencyStore#extendLock} / {@link IdempotencyStore#supportsLockExtension})
 * do <em>not</em> need unwrapping — a decorator must simply override them to
 * forward, or it would inherit the no-op defaults and silently disable the
 * heartbeat. This interface is for the side-interface capabilities only.
 */
public interface DelegatingIdempotencyStore {

    /** The store this one delegates to. Never {@code null}. */
    IdempotencyStore getDelegate();

    /**
     * Unwraps a possibly-decorated store to the innermost concrete store, so a
     * caller can detect side-interface capabilities ({@link IdempotencyStoreHealth})
     * on the real backend regardless of how many decorators sit in front of it.
     */
    static IdempotencyStore unwrap(IdempotencyStore store) {
        IdempotencyStore current = store;
        while (current instanceof DelegatingIdempotencyStore delegating) {
            current = delegating.getDelegate();
        }
        return current;
    }
}
