package io.github.lu1tr0n.idempotency.core;

/**
 * Optional, Actuator-free reachability probe for an {@link IdempotencyStore}.
 *
 * <p>Kept <strong>separate</strong> from {@link IdempotencyStore} on purpose: the
 * store SPI is deliberately tiny and stable, and not every backend can probe its
 * own health cheaply. A store that <em>can</em> implements this so the optional
 * {@code idempotency} Actuator health indicator can surface backend reachability;
 * a store that does not is reported as {@code UNKNOWN} rather than guessed.
 *
 * <p>This interface references no Actuator types, so it imposes nothing on
 * consumers who do not have Spring Boot Actuator on the classpath. The bridge
 * to Actuator's {@code Health} lives entirely in the health indicator.
 *
 * <p>Implementations should keep {@link #verify()} cheap and bounded — it is
 * called on the health-check interval, potentially by several probers
 * (Kubernetes readiness, load balancer, Prometheus) at once. Validate
 * connectivity (and, where free, that the idempotency table/keyspace exists),
 * not data volume. Never scan, never count rows.
 */
public interface IdempotencyStoreHealth {

    /**
     * Cheaply probes the backing store. Returns normally when the store is
     * reachable and usable; throws when it is unreachable or misconfigured
     * (e.g. the idempotency table is missing). The caller — the health
     * indicator — decides how a failure maps to health status based on the
     * configured {@code failure-strategy}.
     *
     * @throws Exception when the store cannot be reached or is misconfigured
     */
    void verify() throws Exception;
}
