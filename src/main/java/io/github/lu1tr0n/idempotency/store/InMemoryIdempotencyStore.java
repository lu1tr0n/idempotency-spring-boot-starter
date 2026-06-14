package io.github.lu1tr0n.idempotency.store;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-process implementation. Suitable for tests and single-instance
 * deployments where idempotency does not need to survive a process restart.
 * <strong>Not</strong> suitable for clustered production deployments — locks
 * are JVM-local, so two instances would not see each other's locks.
 *
 * <p>Memory profile: one entry per active key. Expired entries are lazily
 * evicted on access — adequate for the test usage this class targets. For a
 * long-running single-instance deployment, swap to the JDBC store.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentHashMap<String, Entry> records = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Lock> locks = new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotencyRecord> findRecord(IdempotencyKey key) {
        Entry entry = records.get(key.value());
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.expiresAt)) {
            records.remove(key.value(), entry);
            return Optional.empty();
        }
        return Optional.of(entry.record);
    }

    @Override
    public Optional<LockToken> acquireLock(IdempotencyKey key, Duration ttl) {
        Instant now = Instant.now();
        StringLockToken token = new StringLockToken(UUID.randomUUID().toString());
        Lock candidate = new Lock(token, now.plus(ttl));

        Lock existing = locks.compute(key.value(), (k, current) -> {
            if (current == null || now.isAfter(current.expiresAt)) {
                return candidate;
            }
            return current; // Held by someone else, still valid.
        });

        if (existing == candidate) {
            return Optional.of(token);
        }
        return Optional.empty();
    }

    @Override
    public void save(IdempotencyRecord record, LockToken token) {
        verifyToken(record.key(), token);
        records.put(record.key(), new Entry(record, record.expiresAt()));
        locks.remove(record.key());
    }

    @Override
    public void releaseLock(IdempotencyKey key, LockToken token) {
        verifyToken(key.value(), token);
        locks.remove(key.value());
    }

    private void verifyToken(String key, LockToken token) {
        Lock held = locks.get(key);
        if (held == null) {
            // Either expired (already gone) or the caller hit a race; both
            // are fine — the lock no longer protects anything.
            return;
        }
        if (!held.token.equals(token)) {
            // Token mismatch means the original lock expired and someone else
            // re-acquired it. Refuse to corrupt their lock.
            throw new IllegalStateException(
                "Lock token mismatch for key '" + key + "' — the original lock expired and was re-acquired");
        }
    }

    /** Visible for tests. */
    public int activeRecordCount() {
        return records.size();
    }

    /** Visible for tests. */
    public int activeLockCount() {
        return locks.size();
    }

    /** Clear all state. Visible for tests; do not call from production code. */
    public void clear() {
        records.clear();
        locks.clear();
    }

    private record Entry(IdempotencyRecord record, Instant expiresAt) {}

    private record Lock(StringLockToken token, Instant expiresAt) {}

    /** Opaque-but-comparable token. */
    private record StringLockToken(String value) implements LockToken {}
}
