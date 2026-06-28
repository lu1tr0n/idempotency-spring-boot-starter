package io.github.lu1tr0n.idempotency.health;

import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyProperties;
import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.core.IdempotencyStoreHealth;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;
import io.github.lu1tr0n.idempotency.store.redis.RedisIdempotencyStore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branching + caching behaviour of the health indicator, isolated from any real
 * backend with fake stores. The strategy-aware severity rules and the
 * unsupported-store fallback are the load-bearing logic, so they are pinned
 * here; the JDBC real-probe path is covered separately by
 * {@code HealthIndicatorJdbcIntegrationTest}.
 */
class IdempotencyStoreHealthIndicatorTest {

    private static IdempotencyProperties props(IdempotencyProperties.FailureStrategy strategy, Duration cacheTtl) {
        IdempotencyProperties p = new IdempotencyProperties();
        p.setFailureStrategy(strategy);
        p.getHealth().setCacheTtl(cacheTtl);
        return p;
    }

    @Test
    void healthyStore_reportsUp_withLowCardinalityDetailsOnly() {
        var indicator = new IdempotencyStoreHealthIndicator(
            new InMemoryIdempotencyStore(),
            props(IdempotencyProperties.FailureStrategy.FAIL_CLOSED, Duration.ZERO));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("backend", "in-memory")
            .containsEntry("failureStrategy", "fail-closed");
        // No keys, principals, table names, or row counts leak into details.
        assertThat(health.getDetails()).containsOnlyKeys("backend", "failureStrategy");
    }

    @Test
    void unreachableStore_failClosed_reportsDown() {
        var indicator = new IdempotencyStoreHealthIndicator(
            new ThrowingStore(),
            props(IdempotencyProperties.FailureStrategy.FAIL_CLOSED, Duration.ZERO));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void unreachableStore_failOpen_reportsUpButDegraded() {
        var indicator = new IdempotencyStoreHealthIndicator(
            new ThrowingStore(),
            props(IdempotencyProperties.FailureStrategy.FAIL_OPEN, Duration.ZERO));

        Health health = indicator.health();

        // Fail-open: the app still serves, so DOWN would wrongly depool it.
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("store", "unreachable")
            .containsEntry("degraded", true);
    }

    @Test
    void storeWithoutCapability_reportsUnknownUnsupported() {
        var indicator = new IdempotencyStoreHealthIndicator(
            new NoCapabilityStore(),
            props(IdempotencyProperties.FailureStrategy.FAIL_CLOSED, Duration.ZERO));

        Health health = indicator.health();

        // UNKNOWN sorts least-severe in Spring's default aggregator (HTTP 200),
        // so a custom store never false-fails /health.
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("probe", "unsupported");
        assertThat(health.getDetails()).containsEntry("backend", "custom");
    }

    @Test
    void result_isCachedWithinTtl_thenReprobed() throws InterruptedException {
        var store = new CountingStore();
        var indicator = new IdempotencyStoreHealthIndicator(
            store, props(IdempotencyProperties.FailureStrategy.FAIL_CLOSED, Duration.ofMillis(150)));

        indicator.health();
        indicator.health();
        indicator.health();
        assertThat(store.probes.get()).isEqualTo(1); // served from cache

        Thread.sleep(200);
        indicator.health();
        assertThat(store.probes.get()).isEqualTo(2); // TTL elapsed → re-probed
    }

    @Test
    void zeroTtl_probesEveryCall() {
        var store = new CountingStore();
        var indicator = new IdempotencyStoreHealthIndicator(
            store, props(IdempotencyProperties.FailureStrategy.FAIL_CLOSED, Duration.ZERO));

        indicator.health();
        indicator.health();

        assertThat(store.probes.get()).isEqualTo(2);
    }

    @Test
    void cacheDecoratedStore_isUnwrapped_soHealthAndLabelSurviveWrapping() {
        // Wrapping the JDBC/Redis store in the L1 cache decorator must NOT drop
        // health to UNKNOWN: the decorator does not implement IdempotencyStoreHealth,
        // so the indicator unwraps to the concrete store to find its probe.
        var delegate = new CountingStore(); // implements IdempotencyStoreHealth
        var cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .<String, IdempotencyRecord>weigher((k, r) ->
                io.github.lu1tr0n.idempotency.store.cache.CacheEntryWeights.weigh(r))
            .maximumWeight(1024)
            .build();
        var decorated = new io.github.lu1tr0n.idempotency.store.cache.CachingIdempotencyStore(delegate, cache, 1024);

        var indicator = new IdempotencyStoreHealthIndicator(
            decorated, props(IdempotencyProperties.FailureStrategy.FAIL_CLOSED, Duration.ZERO));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP); // probe ran via unwrap
        assertThat(health.getDetails()).containsEntry("backend", "custom");
        assertThat(delegate.probes.get()).isEqualTo(1);       // the real store was probed
    }

    @Test
    void redisBackend_labelledRedis_onDegradedProbe() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(org.springframework.data.redis.core.RedisCallback.class)))
            .thenThrow(new org.springframework.dao.QueryTimeoutException("redis down"));
        var indicator = new IdempotencyStoreHealthIndicator(
            new RedisIdempotencyStore(template, "idempotency:"),
            props(IdempotencyProperties.FailureStrategy.FAIL_OPEN, Duration.ZERO));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("backend", "redis")
            .containsEntry("degraded", true);
    }

    @Test
    void redisStore_verify_issuesPing() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(template.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(connection);
        // Drive the RedisCallback the store passes to execute(...).
        when(template.execute(any(org.springframework.data.redis.core.RedisCallback.class)))
            .thenAnswer(inv -> ((org.springframework.data.redis.core.RedisCallback<?>) inv.getArgument(0))
                .doInRedis(connection));

        new RedisIdempotencyStore(template, "idempotency:").verify();

        verify(connection).ping();
    }

    /** Store whose probe always fails (store unreachable / table missing). */
    private static final class ThrowingStore extends NoCapabilityStore implements IdempotencyStoreHealth {
        @Override
        public void verify() {
            throw new IllegalStateException("store unreachable");
        }
    }

    /** Store that counts how many times it is probed. */
    private static final class CountingStore extends NoCapabilityStore implements IdempotencyStoreHealth {
        final AtomicInteger probes = new AtomicInteger();
        @Override
        public void verify() {
            probes.incrementAndGet();
        }
    }

    /** Minimal store with no health capability — exercises the UNKNOWN branch. */
    private static class NoCapabilityStore implements IdempotencyStore {
        @Override
        public Optional<IdempotencyRecord> findRecord(IdempotencyKey key) {
            return Optional.empty();
        }
        @Override
        public Optional<LockToken> acquireLock(IdempotencyKey key, Duration ttl) {
            return Optional.empty();
        }
        @Override
        public void save(IdempotencyRecord record, LockToken token) {
        }
        @Override
        public void releaseLock(IdempotencyKey key, LockToken token) {
        }
    }
}
