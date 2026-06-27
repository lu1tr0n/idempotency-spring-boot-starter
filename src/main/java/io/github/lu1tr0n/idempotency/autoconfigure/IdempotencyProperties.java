package io.github.lu1tr0n.idempotency.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;

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

    /** Redis-specific configuration. */
    private final Redis redis = new Redis();

    /**
     * When {@code true} (default), 5xx responses are cached just like 2xx /
     * 3xx / 4xx. Set to {@code false} so a transient downstream failure
     * (502 from an upstream API, 503 during a deploy) does not poison the
     * cache for the full TTL. With {@code false}, the lock is released
     * instead of the record being saved — the next retry can re-attempt the
     * operation cleanly.
     *
     * <p>Default {@code true} keeps backwards-compatibility with the v0.0.1
     * behaviour; many shops prefer {@code false} for production.
     */
    private boolean cache5xx = true;

    /**
     * Whether the stored idempotency key is scoped to the authenticated
     * principal, mitigating the IETF draft §5 data-leak where one user replays
     * another user's cached response by reusing the same key.
     *
     * <ul>
     *   <li>{@link PrincipalBinding#AUTO} (default) — scope to the principal
     *       when the request is authenticated; fall back to the bare key when
     *       it is anonymous, so public endpoints and existing behaviour are
     *       unchanged.</li>
     *   <li>{@link PrincipalBinding#DISABLED} — never scope; the bare key is
     *       used exactly as in v0.0.3.</li>
     *   <li>{@link PrincipalBinding#REQUIRED} — a tracked request that supplies
     *       a key but resolves no principal is refused (422). Use on endpoints
     *       that must never serve a cross-principal replay.</li>
     * </ul>
     */
    private PrincipalBinding principalBinding = PrincipalBinding.AUTO;

    // === Getters / setters ===

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHeaderName() { return headerName; }
    public void setHeaderName(String headerName) { this.headerName = headerName; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }

    /**
     * Backwards-compatible alias for {@link #getTtl()}. Earlier docs and lab
     * profiles used {@code spring.idempotency.default-ttl}, which was silently
     * ignored. Now both names work, with {@code ttl} being the canonical one.
     */
    @Deprecated(since = "0.0.3", forRemoval = false)
    @DeprecatedConfigurationProperty(replacement = "spring.idempotency.ttl", reason = "Renamed to `ttl` to match the getter/setter pair.")
    public Duration getDefaultTtl() { return ttl; }

    @Deprecated(since = "0.0.3", forRemoval = false)
    public void setDefaultTtl(Duration ttl) { this.ttl = ttl; }
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
    public Redis getRedis() { return redis; }
    public boolean isCache5xx() { return cache5xx; }
    public void setCache5xx(boolean cache5xx) { this.cache5xx = cache5xx; }
    public PrincipalBinding getPrincipalBinding() { return principalBinding; }
    public void setPrincipalBinding(PrincipalBinding principalBinding) { this.principalBinding = principalBinding; }

    public enum Backend { AUTO, JDBC, REDIS, IN_MEMORY }
    public enum FailureStrategy { FAIL_OPEN, FAIL_CLOSED }
    public enum PayloadValidation { ENABLED, DISABLED }
    public enum PrincipalBinding { AUTO, DISABLED, REQUIRED }

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

        /**
         * Which schema flavour to use when {@code autoCreateTable} is on.
         * {@link Platform#AUTO} (the default) inspects the JDBC metadata
         * ({@code DatabaseMetaData.getDatabaseProductName()}) at startup and
         * picks Postgres, H2, MySQL, etc. accordingly. Override only if
         * detection fails for a less-common driver.
         */
        private Platform platform = Platform.AUTO;

        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }
        public boolean isAutoCreateTable() { return autoCreateTable; }
        public void setAutoCreateTable(boolean v) { this.autoCreateTable = v; }
        public Platform getPlatform() { return platform; }
        public void setPlatform(Platform platform) { this.platform = platform; }

        /** Schema flavours bundled with the starter. */
        public enum Platform { AUTO, H2, POSTGRES, HSQLDB }
    }

    /** Redis-specific knobs. */
    public static class Redis {
        /**
         * Prefix applied to every Redis key written by the store. Two
         * sub-namespaces are appended internally: {@code lock:} for the
         * short-TTL lock key and {@code rec:} for the long-TTL record key.
         * Defaults to {@code idempotency:}.
         *
         * <p>Override when the same Redis instance is shared by multiple
         * apps and you want app-specific namespacing (e.g.
         * {@code "payments:idempotency:"}).
         */
        private String keyPrefix = "idempotency:";

        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    }
}
