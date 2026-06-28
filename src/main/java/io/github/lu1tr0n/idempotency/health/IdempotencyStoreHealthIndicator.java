package io.github.lu1tr0n.idempotency.health;

import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyProperties;
import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyProperties.FailureStrategy;
import io.github.lu1tr0n.idempotency.core.DelegatingIdempotencyStore;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.core.IdempotencyStoreHealth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.time.Duration;

/**
 * Spring Boot Actuator health indicator for the idempotency store, exposed at
 * {@code /actuator/health} under the {@code idempotency} key.
 *
 * <h2>What it adds over the built-in {@code db}/{@code redis} indicators</h2>
 *
 * <p>Those report bare connectivity. This one delegates to the store's own
 * {@link IdempotencyStoreHealth#verify()} probe, which (for the JDBC backend)
 * also checks that the <em>idempotency table exists</em> — catching the common
 * "migration never ran" misconfiguration that a plain {@code SELECT 1} misses.
 *
 * <h2>Status semantics — tied to {@code failure-strategy}</h2>
 *
 * <p>Severity tracks what the app can actually do when the store is unreachable,
 * because severity is what drives the readiness rollup and HTTP status:
 *
 * <ul>
 *   <li>probe succeeds → {@link Health#up() UP}.</li>
 *   <li>probe fails + {@link FailureStrategy#FAIL_CLOSED} → {@link Health#down() DOWN}:
 *       the app genuinely refuses keyed mutations (503), so DOWN is honest and
 *       legitimately readiness-affecting.</li>
 *   <li>probe fails + {@link FailureStrategy#FAIL_OPEN} → {@code UP} with a
 *       {@code degraded} detail: the app still serves requests (just without the
 *       idempotency guarantee), so reporting DOWN would pull a serving node out
 *       of rotation and manufacture an outage. The failure is logged at WARN and
 *       should be alerted on via the {@code idempotency} metric, not the health
 *       rollup.</li>
 *   <li>store does not implement {@link IdempotencyStoreHealth} (a custom
 *       consumer store) → {@link Health#unknown() UNKNOWN}: honest, and Spring's
 *       default aggregator ranks UNKNOWN least-severe (maps to HTTP 200), so it
 *       never false-fails {@code /health}. Such a store can opt in by
 *       implementing the capability interface.</li>
 * </ul>
 *
 * <p>This indicator is <strong>not</strong> added to the readiness group by
 * default and never to liveness — a store outage must not get a pod killed or
 * (when shared across the fleet) depool every replica at once. Consumers who
 * want store outage to gate traffic add it explicitly:
 * {@code management.endpoint.health.group.readiness.include=readinessState,idempotency}.
 *
 * <h2>Caching</h2>
 *
 * <p>The endpoint is polled by several probers (Kubernetes, load balancer,
 * Prometheus) on a short interval. To decouple scrape rate from backend load and
 * damp flapping, the last result is cached for
 * {@code spring.idempotency.health.cache-ttl} (default 1s; set {@code 0} to probe
 * on every call). A single probe per interval serves all scrapers.
 */
public class IdempotencyStoreHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStoreHealthIndicator.class);

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final long cacheTtlNanos;

    private volatile Snapshot cached;

    public IdempotencyStoreHealthIndicator(IdempotencyStore store, IdempotencyProperties properties) {
        // Unwrap any decorator (e.g. the optional L1 cache) to the concrete store,
        // so the capability probe and backend label reflect the real backend. A
        // decorator does not implement IdempotencyStoreHealth — without unwrapping,
        // wrapping JDBC would silently drop the probe to UNKNOWN.
        this.store = DelegatingIdempotencyStore.unwrap(store);
        this.properties = properties;
        Duration ttl = properties.getHealth().getCacheTtl();
        this.cacheTtlNanos = ttl == null ? 0L : Math.max(0L, ttl.toNanos());
    }

    @Override
    public Health health() {
        if (cacheTtlNanos > 0) {
            Snapshot snapshot = cached;
            if (snapshot != null && System.nanoTime() - snapshot.computedAtNanos() < cacheTtlNanos) {
                return snapshot.health();
            }
        }
        Health health = probe();
        if (cacheTtlNanos > 0) {
            cached = new Snapshot(System.nanoTime(), health);
        }
        return health;
    }

    private Health probe() {
        Health.Builder builder = Health.unknown()
            .withDetail("backend", backendLabel())
            .withDetail("failureStrategy", failureStrategyLabel());

        if (!(store instanceof IdempotencyStoreHealth probe)) {
            // Custom store with no probe — assert nothing we did not verify.
            // Builder already carries UNKNOWN from Health.unknown() above.
            return builder.withDetail("probe", "unsupported").build();
        }

        try {
            probe.verify();
            return builder.up().build();
        } catch (Exception ex) {
            if (properties.getFailureStrategy() == FailureStrategy.FAIL_OPEN) {
                log.warn("Idempotency store probe failed; fail-open is active so requests still "
                    + "proceed without idempotency guarantees", ex);
                return builder.up()
                    .withDetail("store", "unreachable")
                    .withDetail("degraded", true)
                    .withException(ex)
                    .build();
            }
            log.warn("Idempotency store probe failed; fail-closed is active so keyed mutations "
                + "will be rejected with 503", ex);
            return builder.down(ex).build();
        }
    }

    /**
     * Low-cardinality backend label derived from the store's simple class name
     * <em>by string</em> — deliberately not {@code instanceof JdbcIdempotencyStore},
     * which would force-load a class referencing {@code JdbcTemplate}/Redis types
     * that are absent in a single-backend deployment ({@code NoClassDefFoundError}).
     */
    private String backendLabel() {
        return switch (store.getClass().getSimpleName()) {
            case "JdbcIdempotencyStore" -> "jdbc";
            case "RedisIdempotencyStore" -> "redis";
            case "InMemoryIdempotencyStore" -> "in-memory";
            default -> "custom";
        };
    }

    private String failureStrategyLabel() {
        return properties.getFailureStrategy() == FailureStrategy.FAIL_OPEN ? "fail-open" : "fail-closed";
    }

    /** Immutable cached probe result with a monotonic-clock timestamp. */
    private record Snapshot(long computedAtNanos, Health health) {}
}
