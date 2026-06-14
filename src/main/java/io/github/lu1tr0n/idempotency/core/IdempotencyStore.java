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
     * caller A's late {@code save} corrupts caller B's lock.
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

    /** Marker for the opaque token returned from {@link #acquireLock}. */
    interface LockToken {}

    /** Wraps any storage-layer failure. The caller chooses fail-open or fail-closed. */
    class StoreException extends RuntimeException {
        public StoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
