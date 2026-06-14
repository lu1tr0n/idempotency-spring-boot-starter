package io.github.lu1tr0n.idempotency.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Top-level configuration for the idempotency starter, bound from properties
 * with the {@code spring.idempotency} prefix.
 *
 * <p>Example {@code application.yml}:
 * <pre>{@code
 * spring:
 *   idempotency:
 *     enabled: true
 *     header-name: Idempotency-Key
 *     ttl: 24h
 *     lock-timeout: 30s
 *     backend: jdbc                 # jdbc | redis | in-memory | auto
 *     failure-strategy: fail-closed # fail-open | fail-closed
 *     methods: [POST, PUT, PATCH]   # HTTP methods the global filter tracks
 *     payload-validation: enabled   # enabled | disabled
 *     jdbc:
 *       table-name: idempotency_records
 * }</pre>
 */
@ConfigurationProperties(prefix = "spring.idempotency")
public class IdempotencyProperties {

    /**
     * Master switch — when {@code false}, the auto-configuration registers no
     * beans and the filter is not installed. Set to {@code false} in test
     * profiles where idempotency is not exercised.
     */
    private boolean enabled = true;

    /**
     * Name of the HTTP header that carries the idempotency key. Stripe's
     * convention is {@code Idempotency-Key}, which is also the IETF draft
     * recommendation; only override if you have a hard constraint.
     */
    private String headerName = "Idempotency-Key";

    /**
     * How long a stored record remains valid. Defaults to 24 hours, matching
     * Stripe — long enough to handle most retry storms (mobile clients
     * regaining connectivity, batch jobs restarting), short enough to not
     * accumulate dead records.
     */
    private Duration ttl = Duration.ofHours(24);

    /**
     * How long a lock is held before being considered abandoned. Defaults to
     * 30 seconds — longer than any reasonable request, short enough that a
     * crashed app server's locks expire before the next deploy.
     */
    private Duration lockTimeout = Duration.ofSeconds(30);

    /**
     * Which backend to use. {@code AUTO} (the default) picks JDBC when a
     * {@code DataSource} bean is present, Redis when a {@code RedisConnectionFactory}
     * is present and no {@code DataSource} is, and fails loudly if both are
     * present so the user is forced to make the choice explicitly.
     */
    private Backend backend = Backend.AUTO;

    /**
     * What to do when the storage layer is unreachable. {@code FAIL_CLOSED}
     * (the default) refuses to process requests rather than risk a duplicate
     * operation — the safe choice for financial / business-critical
     * endpoints. {@code FAIL_OPEN} lets requests through without idempotency
     * guarantees, appropriate for read-mostly or low-stakes endpoints.
     */
    private FailureStrategy failureStrategy = FailureStrategy.FAIL_CLOSED;

    /**
     * HTTP methods the global filter applies to. Defaults to the
     * mutation-bearing trio — GET / HEAD / OPTIONS are pointless to track
     * because they are idempotent at the protocol level already.
     */
    private Set<String> methods = new LinkedHashSet<>(Set.of("POST", "PUT", "PATCH"));

    /**
     * When {@link PayloadValidation#ENABLED} (the default), a request that
     * reuses an idempotency key but carries a different request body returns
     * 422 instead of the cached response. Disable only if your clients
     * legitimately retry with mutated bodies on the same key (rare).
     */
    private PayloadValidation payloadValidation = PayloadValidation.ENABLED;

    /** JDBC-specific configuration. */
    private final Jdbc jdbc = new Jdbc();

    // === Getters / setters ===

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHeaderName() { return headerName; }
    public void setHeaderName(String headerName) { this.headerName = headerName; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public Duration getLockTimeout() { return lockTimeout; }
    public void setLockTimeout(Duration lockTimeout) { this.lockTimeout = lockTimeout; }
    public Backend getBackend() { return backend; }
    public void setBackend(Backend backend) { this.backend = backend; }
    public FailureStrategy getFailureStrategy() { return failureStrategy; }
    public void setFailureStrategy(FailureStrategy strategy) { this.failureStrategy = strategy; }
    public Set<String> getMethods() { return methods; }
    public void setMethods(Set<String> methods) { this.methods = methods; }
    public PayloadValidation getPayloadValidation() { return payloadValidation; }
    public void setPayloadValidation(PayloadValidation pv) { this.payloadValidation = pv; }
    public Jdbc getJdbc() { return jdbc; }

    public enum Backend { AUTO, JDBC, REDIS, IN_MEMORY }
    public enum FailureStrategy { FAIL_OPEN, FAIL_CLOSED }
    public enum PayloadValidation { ENABLED, DISABLED }

    /** JDBC-specific knobs. */
    public static class Jdbc {
        /**
         * Name of the table that stores idempotency records. The schema is
         * documented in {@code idempotency-schema.sql}; create it with your
         * preferred migration tool (Flyway, Liquibase) or let the
         * {@code spring.idempotency.jdbc.auto-create-table=true} switch
         * create it at startup (not recommended for production).
         */
        private String tableName = "idempotency_records";

        /**
         * When {@code true}, runs {@code CREATE TABLE IF NOT EXISTS} against
         * the datasource at startup. Convenient for tests and local dev;
         * production should manage the schema via Flyway / Liquibase so DDL
         * changes are reviewable and reversible.
         */
        private boolean autoCreateTable = false;

        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }
        public boolean isAutoCreateTable() { return autoCreateTable; }
        public void setAutoCreateTable(boolean v) { this.autoCreateTable = v; }
    }
}
