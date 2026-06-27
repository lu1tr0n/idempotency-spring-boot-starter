package io.github.lu1tr0n.idempotency.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin collaborator that records idempotency outcomes through the Micrometer
 * {@link Observation} API. Because Observation bridges to both a tracer
 * (OpenTelemetry / Brave, producing a span) and a meter registry (producing an
 * {@code idempotency} counter partitioned by outcome), one {@link #record} call
 * feeds traces and metrics at once. The observation wraps no work, so its timer
 * is effectively a per-outcome counter, not a latency measure.
 *
 * <h2>No-op by default</h2>
 * <p>When the consumer has no {@link ObservationRegistry} bean (no Actuator,
 * no tracer) the registry is {@link ObservationRegistry#NOOP} and every call is
 * free — the starter's {@code compileOnly} contract is preserved.
 *
 * <h2>What is never recorded</h2>
 * <p>Only <strong>low-cardinality</strong>, non-sensitive tags are emitted:
 * the outcome enum and the HTTP status. The raw {@code Idempotency-Key} (client
 * data, potential PII, unbounded cardinality) and the principal-scoped key
 * (embeds a hashed principal token) are <strong>never</strong> put on a span or
 * a metric.
 */
public final class IdempotencyObservations {

    static final String OBSERVATION_NAME = "idempotency";
    static final String OUTCOME_KEY = "idempotency.outcome";
    static final String STATUS_KEY = "idempotency.status";

    /**
     * Bounded set of outcomes — the value of the {@code idempotency.outcome}
     * tag. Kept small and stable so it is safe as a metric dimension.
     */
    public enum Outcome {
        /** A cached response was replayed. */
        REPLAYED("replayed"),
        /** The handler ran and its response was cached. */
        EXECUTED_STORED("executed_stored"),
        /** The handler ran but the response was not cached (non-cacheable status or over-cap response). */
        EXECUTED_NOT_STORED("executed_not_stored"),
        /** A concurrent request held the lock (409). */
        LOCK_HELD("lock_held"),
        /** The key was reused with a different payload (422). */
        PAYLOAD_MISMATCH("payload_mismatch"),
        /** The request body exceeded the cap (413). */
        PAYLOAD_TOO_LARGE("payload_too_large");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(IdempotencyObservations.class);

    private final ObservationRegistry registry;
    private final boolean enabled;

    public IdempotencyObservations(ObservationRegistry registry, boolean enabled) {
        this.registry = registry == null ? ObservationRegistry.NOOP : registry;
        this.enabled = enabled;
    }

    /** A disabled, NOOP-backed instance for wiring paths where no registry is available. */
    public static IdempotencyObservations noop() {
        return new IdempotencyObservations(ObservationRegistry.NOOP, false);
    }

    /**
     * Record one idempotency outcome with its resulting HTTP status. Emits an
     * {@code idempotency} observation (span + timer) tagged with the
     * low-cardinality outcome and status. No-op when disabled or when the
     * registry is NOOP.
     */
    public void record(Outcome outcome, int status) {
        if (!enabled || registry.isNoop()) {
            return;
        }
        try {
            Observation.createNotStarted(OBSERVATION_NAME, registry)
                .lowCardinalityKeyValue(OUTCOME_KEY, outcome.tag())
                .lowCardinalityKeyValue(STATUS_KEY, statusTag(status))
                .observe(() -> { });
        } catch (RuntimeException ex) {
            // Observability is strictly best-effort — a faulty ObservationHandler
            // (tracer/meter bridge) must never turn into a request-side failure.
            log.debug("Idempotency observation recording failed: {}", ex.getMessage());
        }
    }

    /**
     * Bound the status tag to the real HTTP status range so an application that
     * reflects an untrusted value into the response status cannot explode metric
     * cardinality through this dimension.
     */
    private static String statusTag(int status) {
        return (status >= 100 && status <= 599) ? Integer.toString(status) : "other";
    }
}
