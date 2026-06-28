package io.github.lu1tr0n.idempotency.store.cache;

import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;

import java.util.List;
import java.util.Map;

/**
 * Estimates the heap weight of an {@link IdempotencyRecord} held in the L1
 * cache, so the cache is bounded by <em>bytes</em> (Caffeine {@code maximumWeight})
 * rather than entry count. A count-based bound is a memory trap here: a record
 * holds the full response body (up to {@code max-response-size}, default 1 MB),
 * so {@code maximumSize=10_000} could mean 10 GB. Weight closes that.
 *
 * <p>The estimate is a fixed per-entry overhead plus the body length plus the
 * character length of every header name and value. It deliberately reads
 * {@link IdempotencyRecord#bodyLength()} (no array clone) and iterates the
 * already-immutable headers map (no copy), so weighing a hot entry is cheap. It
 * is an estimate, not an exact retained-size — close enough to bound RAM, which
 * is all the eviction policy needs.
 *
 * <p>The {@link #ENTRY_OVERHEAD_BYTES} floor matters for the bound to actually
 * hold: without it, an attacker flooding distinct keys against an endpoint with
 * tiny or empty responses would accumulate near-zero-weight entries that barely
 * count against {@code maximum-weight}, while each still retains the record,
 * header collections, key string and Caffeine node on the heap. The floor makes
 * the entry <em>count</em> bounded by {@code maximum-weight / ENTRY_OVERHEAD_BYTES},
 * so the documented RAM ceiling covers the real retained size, not just payload
 * bytes.
 */
public final class CacheEntryWeights {

    /**
     * Conservative fixed cost charged to every entry to cover the heap it retains
     * beyond its payload: the {@code IdempotencyRecord} object, its header
     * {@code Map}/{@code List} structures, the key {@code String}, two
     * {@code Instant}s, and Caffeine's own cache node. Chosen so a 64 MB ceiling
     * caps entry count near ~128k even for empty-body responses.
     */
    static final int ENTRY_OVERHEAD_BYTES = 512;

    private CacheEntryWeights() {}

    /**
     * @return the estimated weight in bytes, clamped to {@code int} for the
     *         Caffeine weigher (a single record never approaches 2 GB).
     */
    public static int weigh(IdempotencyRecord record) {
        long weight = ENTRY_OVERHEAD_BYTES + record.bodyLength();
        for (Map.Entry<String, List<String>> header : record.headers().entrySet()) {
            weight += header.getKey().length();
            for (String value : header.getValue()) {
                weight += value.length();
            }
        }
        return (int) Math.min(weight, Integer.MAX_VALUE);
    }
}
