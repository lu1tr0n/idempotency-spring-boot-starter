package io.github.lu1tr0n.idempotency.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test (TCK) for {@link IdempotencyStore} implementations. A backend
 * author extends this and returns a fresh, empty store from {@link #newStore()};
 * the inherited tests then assert the atomicity and lifecycle guarantees the SPI
 * requires — the same guarantees the built-in JDBC / Redis / in-memory stores
 * are held to.
 *
 * <pre>{@code
 * class DynamoIdempotencyStoreTest extends AbstractIdempotencyStoreContractTest {
 *     @Override protected IdempotencyStore newStore() {
 *         return new DynamoIdempotencyStore(testTableClient());
 *     }
 * }
 * }</pre>
 *
 * <p>Time-based expiry is checked with an already-past {@code expiresAt} rather
 * than a sleep, so the suite is deterministic and does not flake under load.
 * The lock tests assert mutual exclusion sequentially; they do not stress
 * {@code acquireLock} under real concurrency, so a store with a check-then-set
 * race would still pass. Treat these as the lifecycle contract, and cover
 * concurrent atomicity with a backend-specific stress test.
 */
public abstract class AbstractIdempotencyStoreContractTest {

    /** Return a fresh store with no records or locks. Called once per test. */
    protected abstract IdempotencyStore newStore();

    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private static IdempotencyRecord record(String key, int status, byte[] body, Instant expiresAt) {
        return IdempotencyRecord.builder()
            .key(key)
            .statusCode(status)
            .headers(Map.of("Content-Type", List.of("application/json")))
            .body(body)
            .expiresAt(expiresAt)
            .build();
    }

    @Test
    void findRecord_emptyForUnknownKey() {
        IdempotencyStore store = newStore();
        assertTrue(store.findRecord(IdempotencyKey.of("unknown")).isEmpty(),
            "a key that was never used must not return a record");
    }

    @Test
    void acquireLock_firstCallerWins_secondSeesEmpty() {
        IdempotencyStore store = newStore();
        IdempotencyKey key = IdempotencyKey.of("lock-race");
        assertTrue(store.acquireLock(key, LOCK_TTL).isPresent(), "first caller acquires the lock");
        assertTrue(store.acquireLock(key, LOCK_TTL).isEmpty(),
            "acquireLock must be an atomic test-and-set: a second caller sees the lock held");
    }

    @Test
    void findRecord_emptyWhileLockHeldWithoutRecord() {
        IdempotencyStore store = newStore();
        IdempotencyKey key = IdempotencyKey.of("in-flight");
        store.acquireLock(key, LOCK_TTL).orElseThrow();
        assertTrue(store.findRecord(key).isEmpty(),
            "a lock with no saved record yet must read as empty, not as a record");
    }

    @Test
    void saveThenFind_returnsStoredResponse() {
        IdempotencyStore store = newStore();
        IdempotencyKey key = IdempotencyKey.of("save-replay");
        IdempotencyStore.LockToken token = store.acquireLock(key, LOCK_TTL).orElseThrow();
        store.save(record(key.value(), 201, new byte[]{1, 2, 3}, Instant.now().plus(LOCK_TTL)), token);

        Optional<IdempotencyRecord> found = store.findRecord(key);
        assertTrue(found.isPresent(), "a saved record must replay");
        assertEquals(201, found.get().statusCode(), "status round-trips");
        assertEquals(3, found.get().body().length, "body round-trips");
        assertEquals(List.of("application/json"), found.get().headers().get("Content-Type"),
            "headers round-trip");
    }

    @Test
    void save_rejectsWrongToken() {
        IdempotencyStore store = newStore();
        IdempotencyKey key = IdempotencyKey.of("token-check");
        IdempotencyStore.LockToken realToken = store.acquireLock(key, LOCK_TTL).orElseThrow();
        IdempotencyStore.LockToken forged = new IdempotencyStore.LockToken() {};

        assertThrows(RuntimeException.class,
            () -> store.save(record(key.value(), 200, new byte[]{9}, Instant.now().plus(LOCK_TTL)), forged),
            "save must reject a token that does not own the lock (defeats late-writer lock stealing)");

        // The rejected save must not have persisted or corrupted anything: no
        // record, and the real owner can still complete.
        assertTrue(store.findRecord(key).isEmpty(), "a rejected save must not persist a record");
        store.save(record(key.value(), 201, new byte[]{1}, Instant.now().plus(LOCK_TTL)), realToken);
        assertEquals(201, store.findRecord(key).orElseThrow().statusCode(),
            "the real lock owner can still save after a forged save was rejected");
    }

    @Test
    void findRecord_emptyForExpiredRecord() {
        IdempotencyStore store = newStore();
        IdempotencyKey key = IdempotencyKey.of("expired");
        IdempotencyStore.LockToken token = store.acquireLock(key, LOCK_TTL).orElseThrow();
        // Already past: expiry is enforced at read time, so no sleep is needed.
        store.save(record(key.value(), 200, new byte[]{7}, Instant.now().minusSeconds(1)), token);
        assertTrue(store.findRecord(key).isEmpty(),
            "an expired record must not be returned; expiry is enforced on read");
    }

    @Test
    void releaseLock_freesKeyForReacquire_withoutCaching() {
        IdempotencyStore store = newStore();
        IdempotencyKey key = IdempotencyKey.of("release");
        IdempotencyStore.LockToken token = store.acquireLock(key, LOCK_TTL).orElseThrow();
        store.releaseLock(key, token);

        assertTrue(store.findRecord(key).isEmpty(), "releasing a lock must not leave a cached record");
        assertTrue(store.acquireLock(key, LOCK_TTL).isPresent(),
            "after release the key is free to lock again (a failed operation can retry)");
    }

    @Test
    void supportsLockExtension_isHonoured() {
        IdempotencyStore store = newStore();
        IdempotencyKey key = IdempotencyKey.of("extend");
        IdempotencyStore.LockToken token = store.acquireLock(key, LOCK_TTL).orElseThrow();

        if (store.supportsLockExtension()) {
            assertTrue(store.extendLock(key, token, LOCK_TTL),
                "the current owner must be able to extend its lock");
            IdempotencyStore.LockToken forged = new IdempotencyStore.LockToken() {};
            assertFalse(store.extendLock(key, forged, LOCK_TTL),
                "a non-owner token must not extend the lock");
        } else {
            // Default contract: a store that does not support extension returns
            // true ("nothing to revoke") so the no-op heartbeat is harmless.
            assertTrue(store.extendLock(key, token, LOCK_TTL),
                "the default extendLock is a no-op returning true");
        }
    }
}
