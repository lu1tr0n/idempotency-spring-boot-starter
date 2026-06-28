package io.github.lu1tr0n.idempotency.store.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.lu1tr0n.idempotency.core.DelegatingIdempotencyStore;
import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correctness contract of the L1 cache decorator, against a counting fake L2.
 * The load-bearing invariants are pinned here: positives-only caching, the
 * read-time expiry re-check that defeats cross-TTL stale replay, the lock never
 * being cached, write-through on save, and capability forwarding.
 */
class CachingIdempotencyStoreTest {

    private static final long MAX_ENTRY_BYTES = 256 * 1024;

    private final CountingStore delegate = new CountingStore();
    private final Cache<String, IdempotencyRecord> l1 = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(2))
        .maximumWeight(64L * 1024 * 1024)
        .weigher((String k, IdempotencyRecord r) -> CacheEntryWeights.weigh(r))
        .build();
    private final CachingIdempotencyStore store = new CachingIdempotencyStore(delegate, l1, MAX_ENTRY_BYTES);

    @Test
    void replay_isServedFromL1_afterFirstLoad() {
        IdempotencyKey key = IdempotencyKey.of("hot");
        delegate.put(record("hot", Instant.now().plusSeconds(3600), 64));

        assertThat(store.findRecord(key)).isPresent();
        assertThat(store.findRecord(key)).isPresent();
        assertThat(store.findRecord(key)).isPresent();

        // First call hit L2 and populated L1; the rest were L1 hits.
        assertThat(delegate.findCalls.get()).isEqualTo(1);
    }

    @Test
    void absentKey_isNeverCached() {
        IdempotencyKey key = IdempotencyKey.of("missing");

        assertThat(store.findRecord(key)).isEmpty();
        assertThat(store.findRecord(key)).isEmpty();

        // No negative caching: every miss re-checks L2 (so a later save elsewhere
        // is seen, never masked by a cached "absent").
        assertThat(delegate.findCalls.get()).isEqualTo(2);
    }

    @Test
    void expiredL1Entry_fallsThroughToL2_andReturnsTheNewerRecord() throws InterruptedException {
        IdempotencyKey key = IdempotencyKey.of("reused");
        // First record expires almost immediately; write-through puts it in L1.
        IdempotencyRecord first = record("reused", Instant.now().plusMillis(40), 64, "hash-A");
        store.save(first, CountingStore.TOKEN);

        // The key is reused after expiry: L2 now holds a different record.
        Thread.sleep(60);
        delegate.put(record("reused", Instant.now().plusSeconds(3600), 64, "hash-B"));

        Optional<IdempotencyRecord> result = store.findRecord(key);

        // The stale L1 entry must NOT be replayed — the read-time expiry re-check
        // drops it and L2 supplies the current record.
        assertThat(result).isPresent();
        assertThat(result.get().payloadHash()).isEqualTo("hash-B");
        assertThat(delegate.findCalls.get()).isEqualTo(1); // L2 consulted after L1 invalidation
    }

    @Test
    void oversizeRecord_isReturnedButNotCached() {
        IdempotencyKey key = IdempotencyKey.of("big");
        delegate.put(record("big", Instant.now().plusSeconds(3600), (int) MAX_ENTRY_BYTES + 1));

        assertThat(store.findRecord(key)).isPresent();
        assertThat(store.findRecord(key)).isPresent();

        // Too large for L1 → served from L2 both times, never populated.
        assertThat(delegate.findCalls.get()).isEqualTo(2);
    }

    @Test
    void weigh_chargesPerEntryOverheadFloor_plusPayload() {
        // The floor is the security fix that bounds entry count for tiny/empty
        // bodies; pin it directly so removing it fails a test, not just silently
        // regresses the RAM bound.
        IdempotencyRecord empty = IdempotencyRecord.builder()
            .key("k").body(new byte[0]).payloadHash("h")
            .expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(CacheEntryWeights.weigh(empty)).isEqualTo(CacheEntryWeights.ENTRY_OVERHEAD_BYTES);

        IdempotencyRecord withPayload = IdempotencyRecord.builder()
            .key("k").body(new byte[100]).addHeader("X-Test", "abc").payloadHash("h")
            .expiresAt(Instant.now().plusSeconds(60)).build();
        // overhead + 100 body bytes + "X-Test"(6) + "abc"(3)
        assertThat(CacheEntryWeights.weigh(withPayload))
            .isEqualTo(CacheEntryWeights.ENTRY_OVERHEAD_BYTES + 100 + 6 + 3);
    }

    @Test
    void save_doesNotPopulateL1_whenDelegateRejects() {
        IdempotencyKey key = IdempotencyKey.of("rejected");
        delegate.failSave = true;

        // delegate.save throws → the decorator must NOT cache a record L2 refused
        // (token mismatch / store down), else this node would replay an
        // uncommitted write.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                store.save(record("rejected", Instant.now().plusSeconds(3600), 64), CountingStore.TOKEN))
            .isInstanceOf(IdempotencyStore.StoreException.class);

        assertThat(store.findRecord(key)).isEmpty();      // L1 was not populated
        assertThat(delegate.findCalls.get()).isEqualTo(1); // fell through to L2
    }

    @Test
    void save_doesNotPopulateL1_whenRecordIsOverCap() {
        IdempotencyKey key = IdempotencyKey.of("bigsave");
        store.save(record("bigsave", Instant.now().plusSeconds(3600), (int) MAX_ENTRY_BYTES + 1),
            CountingStore.TOKEN);

        // Drop the L2 copy: if the over-cap record had been cached, findRecord
        // would still serve it. It must miss and fall through.
        delegate.clear();
        assertThat(store.findRecord(key)).isEmpty();
    }

    @Test
    void acquireLock_alwaysDelegates_neverCached() {
        IdempotencyKey key = IdempotencyKey.of("lock");

        store.acquireLock(key, Duration.ofSeconds(30));
        store.acquireLock(key, Duration.ofSeconds(30));

        assertThat(delegate.acquireCalls.get()).isEqualTo(2);
    }

    @Test
    void save_writesThrough_soNextReplaySkipsL2() {
        IdempotencyKey key = IdempotencyKey.of("wt");

        store.save(record("wt", Instant.now().plusSeconds(3600), 64), CountingStore.TOKEN);
        assertThat(delegate.saveCalls.get()).isEqualTo(1);

        assertThat(store.findRecord(key)).isPresent();
        assertThat(delegate.findCalls.get()).isZero(); // served from the write-through entry
    }

    @Test
    void releaseLock_invalidatesL1() {
        IdempotencyKey key = IdempotencyKey.of("rel");
        store.save(record("rel", Instant.now().plusSeconds(3600), 64), CountingStore.TOKEN);

        store.releaseLock(key, CountingStore.TOKEN);

        // Drop the L2 copy too: if L1 had not been invalidated, findRecord would
        // still return the stale entry. It must now miss.
        delegate.clear();
        assertThat(store.findRecord(key)).isEmpty();
    }

    @Test
    void lockExtensionCapability_isForwarded() {
        assertThat(store.supportsLockExtension()).isTrue();

        store.extendLock(IdempotencyKey.of("ext"), CountingStore.TOKEN, Duration.ofSeconds(30));
        assertThat(delegate.extendCalls.get()).isEqualTo(1);

        delegate.supportsExtension = false;
        assertThat(store.supportsLockExtension()).isFalse();
    }

    @Test
    void unwrap_resolvesToInnermostDelegate() {
        assertThat(store.getDelegate()).isSameAs(delegate);
        assertThat(DelegatingIdempotencyStore.unwrap(store)).isSameAs(delegate);
        assertThat(DelegatingIdempotencyStore.unwrap(delegate)).isSameAs(delegate);
    }

    private static IdempotencyRecord record(String key, Instant expiresAt, int bodySize) {
        return record(key, expiresAt, bodySize, "hash");
    }

    private static IdempotencyRecord record(String key, Instant expiresAt, int bodySize, String hash) {
        return IdempotencyRecord.builder()
            .key(key)
            .statusCode(200)
            .body(new byte[bodySize])
            .payloadHash(hash)
            .expiresAt(expiresAt)
            .build();
    }

    /** Counting fake L2 store with a backing map. */
    private static final class CountingStore implements IdempotencyStore {
        static final LockToken TOKEN = new LockToken() {};

        final AtomicInteger findCalls = new AtomicInteger();
        final AtomicInteger acquireCalls = new AtomicInteger();
        final AtomicInteger saveCalls = new AtomicInteger();
        final AtomicInteger extendCalls = new AtomicInteger();
        final Map<String, IdempotencyRecord> backing = new ConcurrentHashMap<>();
        boolean supportsExtension = true;
        boolean failSave = false;

        void put(IdempotencyRecord record) { backing.put(record.key(), record); }
        void clear() { backing.clear(); }

        @Override
        public Optional<IdempotencyRecord> findRecord(IdempotencyKey key) {
            findCalls.incrementAndGet();
            return Optional.ofNullable(backing.get(key.value()));
        }

        @Override
        public Optional<LockToken> acquireLock(IdempotencyKey key, Duration ttl) {
            acquireCalls.incrementAndGet();
            return Optional.of(TOKEN);
        }

        @Override
        public void save(IdempotencyRecord record, LockToken token) {
            saveCalls.incrementAndGet();
            if (failSave) {
                throw new StoreException("simulated save failure", null);
            }
            backing.put(record.key(), record);
        }

        @Override
        public void releaseLock(IdempotencyKey key, LockToken token) {
        }

        @Override
        public boolean extendLock(IdempotencyKey key, LockToken token, Duration ttl) {
            extendCalls.incrementAndGet();
            return true;
        }

        @Override
        public boolean supportsLockExtension() {
            return supportsExtension;
        }
    }
}
