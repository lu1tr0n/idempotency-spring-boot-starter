package io.github.lu1tr0n.idempotency.autoconfigure;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link IdempotencyProperties#shouldCache(int)} — the single
 * decision point folding the {@code cache-5xx} and {@code non-cacheable-statuses}
 * opt-outs.
 */
class ShouldCacheTest {

    @Test
    void cachesEverythingByDefault() {
        IdempotencyProperties props = new IdempotencyProperties();
        assertThat(props.shouldCache(200)).isTrue();
        assertThat(props.shouldCache(400)).isTrue();
        assertThat(props.shouldCache(409)).isTrue();
        assertThat(props.shouldCache(500)).isTrue();
    }

    @Test
    void doesNotCacheListedStatuses() {
        IdempotencyProperties props = new IdempotencyProperties();
        props.setNonCacheableStatuses(Set.of(400, 429));
        assertThat(props.shouldCache(400)).isFalse();
        assertThat(props.shouldCache(429)).isFalse();
        // Unlisted ones still cache.
        assertThat(props.shouldCache(200)).isTrue();
        assertThat(props.shouldCache(409)).isTrue();
    }

    @Test
    void cache5xxOff_skips5xxIndependentlyOfTheSet() {
        IdempotencyProperties props = new IdempotencyProperties();
        props.setCache5xx(false);
        assertThat(props.shouldCache(500)).isFalse();
        assertThat(props.shouldCache(503)).isFalse();
        // 4xx still cached unless listed.
        assertThat(props.shouldCache(400)).isTrue();
        // Boundary: 5xx gate is [500,600); 499 and 600 are not 5xx.
        assertThat(props.shouldCache(499)).isTrue();
        assertThat(props.shouldCache(600)).isTrue();
    }

    @Test
    void listedFiveXx_isReleasedEvenWhenCache5xxOn() {
        IdempotencyProperties props = new IdempotencyProperties();
        // cache5xx defaults true, but an explicitly listed 503 still opts out.
        props.setNonCacheableStatuses(Set.of(503));
        assertThat(props.shouldCache(503)).isFalse();
        assertThat(props.shouldCache(500)).isTrue();
    }

    @Test
    void bothOptOutsCombine() {
        IdempotencyProperties props = new IdempotencyProperties();
        props.setCache5xx(false);
        props.setNonCacheableStatuses(Set.of(400));
        assertThat(props.shouldCache(400)).isFalse();
        assertThat(props.shouldCache(503)).isFalse();
        assertThat(props.shouldCache(200)).isTrue();
    }
}
