package io.github.lu1tr0n.idempotency.core;

import java.time.Duration;
import java.util.Optional;

/**
 * Storage SPI for idempotency records. Backends implement this and the
 * auto-configuration picks the right one based on classpath + bean presence:
 *
 * <ul>
 *   <li>{@code spring.idempotency.backend=jdbc} (or autodetect when only a
 *       {@code DataSource} bean is present) → {@code JdbcIdempotencyStore}.</li>
 *   <li>{@code spring.idempotency.backend=redis} (or autodetect when only a
 *       {@code RedisConnectionFactory} bean is present) → {@code RedisIdempotencyStore}.</li>
 *   <li>Test scope → {@code InMemoryIdempotencyStore}, supplied by consumer code.</li>
 * </ul>
 *
 * <p>The contract is small on purpose so adding a new backend (DynamoDB,
 * Cassandra, Mongo) does not require touching the filter or AOP logic.
 *
 * <h2>Atomicity contract</h2>
 *
 * <p>{@link #acquireLock(IdempotencyKey, Duration)} <strong>must</strong> be
 * atomic — the test-and-set on the key has to succeed for at most one caller
 * across the cluster. Implementations on Redis use {@code SET key NX PX ttl};
 * on JDBC use a unique constraint on {@code idempotency_key} + insert with
 * {@code ON CONFLICT DO NOTHING}. A loose implementation here defeats the
 * entire point of distributed idempotency.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>{@code findRecord(key)} — if hit and not expired, replay.</li>
 *   <li>{@code acquireLock(key, ttl)} — if lock acquired, the caller owns it and proceeds.
 *       If lock is held by someone else, the caller sees an empty {@link Optional}
 *       and the filter returns 409 Conflict.</li>
 *   <li>{@code save(record)} — after the protected operation completes, store the
 *       full response and release the lock atomically.</li>
 *   <li>If the protected operation throws, the caller invokes {@link #releaseLock}
 *       so retries can proceed — exceptions are not cached.</li>
 * </ol>
 */
public interface IdempotencyStore {

    /**
     * Looks up a previously-stored record. Returns empty if the key has never
     * been used, the record has expired, or only a lock (no record yet) exists.
     *
     * <p>Implementations must not return expired records; expiry is enforced
     * at read time, not just by background eviction.
     */
    Optional<IdempotencyRecord> findRecord(IdempotencyKey key);

    /**
     * Attempts to acquire an exclusive lock on the key for {@code ttl}.
     *
     * @return a non-empty {@code Optional} containing the lock token when the
     *         lock was acquired by this call. Empty when the key is already
     *         locked by another caller — the filter returns 409 Conflict.
     */
    Optional<LockToken> acquireLock(IdempotencyKey key, Duration ttl);

    /**
     * Persists the completed record and releases the matching lock atomically.
     * The {@code token} must match the token returned from {@link #acquireLock}
     * — implementations reject mismatched tokens to prevent a lock-stealing
     * race where caller A's lock expires, caller B acquires a new lock, and
     * caller A's late {@code save} corrupts caller B's lock. On a token
     * mismatch (or a lock that no longer exists) the implementation
     * <strong>must throw</strong> a {@link RuntimeException} and must
     * <strong>not</strong> silently persist the record — the caller relies on
     * the exception to learn its lock was lost. The three built-in stores throw
     * {@link IllegalStateException}; the storage contract test enforces this.
     *
     * <p>Throws {@link StoreException} when the underlying storage is
     * unavailable; the caller decides whether to fail-open or fail-closed
     * based on {@code spring.idempotency.failure-strategy}.
     */
    void save(IdempotencyRecord record, LockToken token);

    /**
     * Explicitly releases a lock without persisting a record. Used when the
     * protected operation throws — exceptions are not cached, so the next
     * retry must be able to acquire a fresh lock.
     */
    void releaseLock(IdempotencyKey key, LockToken token);

    /**
     * Extends the lock held under {@code token} for another {@code ttl}, used by
     * the optional lock-extension heartbeat to keep a long-running handler's lock
     * from expiring mid-flight (which would let a concurrent retry steal it and
     * re-run the operation).
     *
     * <p>Must be <strong>token-checked</strong>: only the current owner extends,
     * so a lock that already expired and was re-acquired by someone else, or that
     * {@link #save}/{@link #releaseLock} already cleared, is <em>not</em> revived.
     *
     * @return {@code true} if the lock is still held by this token and was
     *         extended; {@code false} if the token no longer owns the lock (the
     *         caller should stop the heartbeat — the operation is now running
     *         without idempotency protection).
     *
     * <p>The default is a no-op returning {@code true} ("nothing to revoke"), so a
     * store that does not implement extension keeps plain acquire-time TTL expiry.
     * Such a store should also leave {@link #supportsLockExtension()} at
     * {@code false} so the heartbeat is not silently believed to be active.
     */
    default boolean extendLock(IdempotencyKey key, LockToken token, Duration ttl) {
        return true;
    }

    /**
     * Whether this store implements {@link #extendLock} with real semantics.
     * Default {@code false}: the auto-configuration warns and disables the
     * heartbeat when {@code spring.idempotency.lock-extension.enabled=true} but
     * the active store cannot actually extend, so operators are never lulled into
     * believing a long handler is protected when it is not.
     */
    default boolean supportsLockExtension() {
        return false;
    }

    /** Marker for the opaque token returned from {@link #acquireLock}. */
    interface LockToken {}

    /** Wraps any storage-layer failure. The caller chooses fail-open or fail-closed. */
    class StoreException extends RuntimeException {
        public StoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
