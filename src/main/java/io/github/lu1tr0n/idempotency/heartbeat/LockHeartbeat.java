package io.github.lu1tr0n.idempotency.heartbeat;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;

/**
 * Optional, opt-in lock-extension heartbeat. While a protected handler runs, the
 * heartbeat periodically calls {@link IdempotencyStore#extendLock} so a lock
 * cannot expire mid-flight and be stolen by a concurrent retry (which would
 * re-run the side-effecting operation).
 *
 * <p>This <strong>narrows but does not close</strong> the duplicate-execution
 * window: a stop-the-world pause or a store partition longer than the lock
 * timeout still lets the lease lapse. It is not an at-most-once guarantee — see
 * the README "Lock extension" section.
 *
 * <p>Driven by the servlet and reactive filters the same way — {@link #start}
 * right after acquiring the lock, {@link Handle#stop()} in a {@code finally} /
 * {@code doFinally} once the handler has completed (or been cancelled). The AOP
 * {@code @Idempotent} path does not yet drive the heartbeat; a long-running
 * annotated method relies on plain {@code lock-timeout} expiry.
 */
public interface LockHeartbeat {

    /**
     * Begins renewing the lock held under {@code token} until the returned
     * {@link Handle} is stopped (or an internal safety cap trips). Implementations
     * must never throw from here or from the renewal — a heartbeat failure must
     * not affect the request.
     */
    Handle start(IdempotencyKey key, IdempotencyStore.LockToken token);

    /** Cancels an in-progress heartbeat. {@link #stop()} is idempotent. */
    @FunctionalInterface
    interface Handle {
        void stop();

        /** A handle that does nothing — returned by {@link LockHeartbeat#NOOP}. */
        Handle NOOP = () -> { };
    }

    /** Disabled heartbeat: every {@link #start} is a no-op. */
    LockHeartbeat NOOP = (key, token) -> Handle.NOOP;
}
