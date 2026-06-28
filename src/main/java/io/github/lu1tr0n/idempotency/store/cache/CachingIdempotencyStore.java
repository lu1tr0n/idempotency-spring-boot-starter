package io.github.lu1tr0n.idempotency.store.cache;

import com.github.benmanes.caffeine.cache.Cache;

import io.github.lu1tr0n.idempotency.core.DelegatingIdempotencyStore;
import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * An optional in-process <strong>L1</strong> cache placed in front of a
 * distributed <strong>L2</strong> store (JDBC or Redis), to short-circuit hot
 * <em>replays</em> of completed idempotency records without a network/DB round
 * trip on every retry.
 *
 * <h2>Why this is safe for a correctness primitive</h2>
 *
 * <p>Idempotency is an at-most-once guarantee, not a best-effort cache, so the
 * L1 may only do things that cannot manufacture a second execution or a wrong
 * replay. It rests on a few load-bearing invariants:
 *
 * <ul>
 *   <li><strong>Only {@link #findRecord} positives are cached.</strong> A
 *       completed {@link IdempotencyRecord} is immutable once {@link #save saved}
 *       — there is no update path — so a cached hit can never go stale within the
 *       record's own lifetime.</li>
 *   <li><strong>{@link #acquireLock} is never cached and never gated by L1.</strong>
 *       It passes straight through to the L2 store, whose atomic test-and-set is
 *       the sole double-execution gate. A node with an L1 hit serves a replay and
 *       never executes; a node without one behaves exactly as if there were no
 *       L1. L1 can therefore never produce a second {@code acquireLock} success
 *       for a live key.</li>
 *   <li><strong>Absent keys are never cached.</strong> Caching a "not found"
 *       would let a node keep missing after another node saved the record, fall
 *       through to {@code acquireLock}, and re-execute. Only present records
 *       enter L1.</li>
 *   <li><strong>Read-time expiry re-check.</strong> Every L1 hit is re-validated
 *       against the record's own {@code expiresAt}; an expired entry is evicted
 *       and the read falls through to L2. This defeats the cross-TTL hazard where
 *       a key is reused with a different payload after its first record expired —
 *       without the re-check, a stale L1 entry could replay the wrong response.
 *       Correctness here depends on this check, not on Caffeine's wall-clock vs
 *       monotonic eviction timing.</li>
 * </ul>
 *
 * <h2>Capability forwarding</h2>
 *
 * <p>The decorator forwards {@link #extendLock}/{@link #supportsLockExtension}
 * to the delegate (otherwise the heartbeat would see the SPI no-op defaults and
 * silently stop protecting long handlers) and implements
 * {@link DelegatingIdempotencyStore} so the health indicator can unwrap to the
 * concrete store and still find its {@code IdempotencyStoreHealth} probe.
 *
 * <h2>Populate paths</h2>
 *
 * <ul>
 *   <li>{@link #findRecord} miss → load from L2; if present, unexpired and within
 *       the per-entry size cap, populate L1.</li>
 *   <li>{@link #save} → write-through: after the delegate persists, populate L1
 *       so the very next replay on this node is served locally.</li>
 * </ul>
 *
 * <p>Stampede note: on an L1 miss for a hot key, concurrent threads each do one
 * L2 {@code findRecord} until the first populate — identical to the no-L1
 * behaviour today. A Caffeine loading {@code get(key, fn)} would collapse those,
 * but its return value is both "what to cache" and "what to return", which
 * conflicts with returning (but not caching) an over-cap record. The L1's value
 * is the steady-state hot path, which the fast {@code getIfPresent} already
 * serves, so explicit populate is the correct trade here.
 */
public class CachingIdempotencyStore implements IdempotencyStore, DelegatingIdempotencyStore {

    private final IdempotencyStore delegate;
    private final Cache<String, IdempotencyRecord> l1;
    private final long maxEntryBytes;

    /**
     * @param delegate      the L2 store (JDBC/Redis) — the source of truth.
     * @param l1            the Caffeine cache, already configured with the
     *                      weigher, {@code maximumWeight} and {@code expireAfterWrite}.
     * @param maxEntryBytes records whose estimated weight exceeds this are served
     *                      from L2 but never populated into L1, so a few large
     *                      bodies cannot dominate the working set.
     */
    public CachingIdempotencyStore(IdempotencyStore delegate, Cache<String, IdempotencyRecord> l1, long maxEntryBytes) {
        this.delegate = delegate;
        this.l1 = l1;
        this.maxEntryBytes = maxEntryBytes;
    }

    @Override
    public IdempotencyStore getDelegate() {
        return delegate;
    }

    @Override
    public Optional<IdempotencyRecord> findRecord(IdempotencyKey key) {
        String cacheKey = key.value();
        IdempotencyRecord hit = l1.getIfPresent(cacheKey);
        if (hit != null) {
            if (isLive(hit)) {
                return Optional.of(hit);
            }
            // Past its own expiry — never replay it; drop and fall through to L2,
            // which may hold a newer record for a reused key.
            l1.invalidate(cacheKey);
        }

        Optional<IdempotencyRecord> loaded = delegate.findRecord(key);
        loaded.ifPresent(record -> {
            if (isLive(record) && isCacheable(record)) {
                l1.put(cacheKey, record);
            }
        });
        return loaded;
    }

    @Override
    public Optional<LockToken> acquireLock(IdempotencyKey key, Duration ttl) {
        // The atomic, cluster-wide gate. Never consult or populate L1 here.
        return delegate.acquireLock(key, ttl);
    }

    @Override
    public void save(IdempotencyRecord record, LockToken token) {
        // Persist to L2 first; only a durably-saved record is safe to cache.
        delegate.save(record, token);
        if (isLive(record) && isCacheable(record)) {
            l1.put(record.key(), record);
        }
    }

    @Override
    public void releaseLock(IdempotencyKey key, LockToken token) {
        delegate.releaseLock(key, token);
        // A released key was never saved, so it should not be in L1 — but evict
        // defensively in case a custom delegate behaves differently. Cheap.
        l1.invalidate(key.value());
    }

    @Override
    public boolean extendLock(IdempotencyKey key, LockToken token, Duration ttl) {
        return delegate.extendLock(key, token, ttl);
    }

    @Override
    public boolean supportsLockExtension() {
        return delegate.supportsLockExtension();
    }

    private static boolean isLive(IdempotencyRecord record) {
        return record.expiresAt() != null && Instant.now().isBefore(record.expiresAt());
    }

    private boolean isCacheable(IdempotencyRecord record) {
        return CacheEntryWeights.weigh(record) <= maxEntryBytes;
    }
}
