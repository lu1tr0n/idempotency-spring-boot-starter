package io.github.lu1tr0n.idempotency.autoconfigure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;
import io.github.lu1tr0n.idempotency.store.cache.CacheEntryWeights;
import io.github.lu1tr0n.idempotency.store.cache.CachingIdempotencyStore;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Wires the optional L1 cache ({@link CachingIdempotencyStore}) in front of the
 * selected distributed store, when {@code spring.idempotency.cache.enabled=true}
 * and Caffeine is on the classpath.
 *
 * <h2>Why this shape</h2>
 *
 * <ul>
 *   <li><strong>Runs after</strong> the three store auto-configs so the backend
 *       store bean already exists, and consumes it via the {@code delegate}
 *       method parameter (Spring excludes the bean being created when resolving
 *       its own parameters, so this never self-injects).</li>
 *   <li>Registers the decorator {@link Primary @Primary} so both the filter and
 *       the health indicator inject the <em>same</em> cached view of the store.</li>
 *   <li>Decorates whatever store was selected — including a user-supplied custom
 *       store — but only once: {@link ConditionalOnMissingBean} on the decorator
 *       type prevents a double-wrap if a consumer registers their own.</li>
 *   <li><strong>Skips</strong> wrapping an in-memory backend (an L1 in front of
 *       an in-process map is pure overhead) and logs INFO instead of silently
 *       double-caching.</li>
 * </ul>
 *
 * <p>Metrics live in a nested config gated on {@code MeterRegistry} so a
 * Micrometer-free app never loads the binder class.
 */
@AutoConfiguration(after = {
    JdbcStoreAutoConfiguration.class,
    RedisStoreAutoConfiguration.class,
    InMemoryStoreAutoConfiguration.class
})
@ConditionalOnClass(Caffeine.class)
@ConditionalOnProperty(prefix = "spring.idempotency.cache", name = "enabled", havingValue = "true")
@ConditionalOnBean(IdempotencyStore.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class CachingStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CachingStoreAutoConfiguration.class);

    /**
     * The L1 cache, exposed as a bean so the metrics binder (nested config) can
     * bind it. Qualified by name to avoid colliding with any Caffeine cache the
     * consumer runs for their own purposes.
     */
    @Bean
    @ConditionalOnMissingBean(name = "idempotencyL1Cache")
    public Cache<String, IdempotencyRecord> idempotencyL1Cache(IdempotencyProperties properties) {
        IdempotencyProperties.Cache cfg = properties.getCache();
        // The weigher narrows the builder's type parameters via its fluent
        // return, so type the variable to the narrowed Caffeine, not Object/Object.
        Caffeine<String, IdempotencyRecord> builder = Caffeine.newBuilder()
            .expireAfterWrite(cfg.getTtl())
            .maximumWeight(cfg.getMaximumWeight().toBytes())
            .weigher((String key, IdempotencyRecord record) -> CacheEntryWeights.weigh(record));
        if (cfg.isRecordStats()) {
            builder.recordStats();
        }
        return builder.build();
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(CachingIdempotencyStore.class)
    public IdempotencyStore cachingIdempotencyStore(
            IdempotencyStore delegate,
            @Qualifier("idempotencyL1Cache") Cache<String, IdempotencyRecord> l1Cache,
            IdempotencyProperties properties) {
        if (delegate instanceof InMemoryIdempotencyStore) {
            // The idempotencyL1Cache bean (and its metrics binder) still exist on
            // this path but are never written to — harmless dead wiring, the cost
            // of keeping the cache bean separately injectable for the metrics
            // config. cache.enabled against an in-memory backend is a misconfig
            // anyway, surfaced by this log.
            log.info("Idempotency: cache.enabled=true but the backend is in-memory; "
                + "an L1 cache in front of an in-process store adds no value — using the store directly.");
            return delegate;
        }
        long maxEntryBytes = properties.getCache().getMaxEntrySize().toBytes();
        log.info("Idempotency: L1 cache active (ttl={}, maximum-weight={}, max-entry-size={}) in front of {} store",
            properties.getCache().getTtl(),
            properties.getCache().getMaximumWeight(),
            properties.getCache().getMaxEntrySize(),
            delegate.getClass().getSimpleName());
        return new CachingIdempotencyStore(delegate, l1Cache, maxEntryBytes);
    }

    /**
     * Publishes Caffeine hit/miss/eviction stats under {@code idempotency.l1}.
     * Declared as an unconditional {@link MeterBinder} bean (the Spring Boot idiom
     * for shipped binders): Spring Boot binds it to every {@code MeterRegistry}
     * that exists and leaves it inert when none does — so this avoids the
     * {@code @ConditionalOnBean(MeterRegistry)} ordering trap, where the registry
     * bean may not be defined yet when this auto-config is evaluated. The binder
     * class is referenced only inside this config, gated on Micrometer being on
     * the classpath, so a Micrometer-free app never loads it.
     *
     * <p>The {@code @ConditionalOnProperty} mirrors the enclosing class on
     * purpose. Without it this member config is evaluated even when the cache is
     * disabled (observed: the binder is created while the outer class's own
     * {@code idempotencyL1Cache} bean is not), so the binder then fails to resolve
     * the absent cache bean and breaks context startup. Gating it on the same
     * property keeps it inert unless the cache is actually on — and when it is on,
     * the outer class always defines {@code idempotencyL1Cache}, which the binder's
     * by-name parameter pulls in as a hard dependency (so Spring instantiates the
     * cache bean first, no {@code @ConditionalOnBean} ordering dance needed).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterBinder.class)
    @ConditionalOnProperty(prefix = "spring.idempotency.cache", name = "enabled", havingValue = "true")
    static class CacheMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "idempotencyL1CacheMetrics")
        public MeterBinder idempotencyL1CacheMetrics(
                @Qualifier("idempotencyL1Cache") Cache<String, IdempotencyRecord> l1Cache) {
            return new CaffeineCacheMetrics<>(l1Cache, "idempotency.l1", List.of());
        }
    }
}
