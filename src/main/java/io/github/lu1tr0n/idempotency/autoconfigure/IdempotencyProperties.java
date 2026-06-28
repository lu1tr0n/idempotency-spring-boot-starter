package io.github.lu1tr0n.idempotency.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;
import org.springframework.util.unit.DataSize;

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

    /** Observation (tracing + metrics) configuration. */
    private final Observations observations = new Observations();

    /** Actuator health-indicator configuration. */
    private final Health health = new Health();

    /** Lock-extension (heartbeat) configuration. */
    private final LockExtension lockExtension = new LockExtension();

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

    /**
     * Maximum request-body size the filter will buffer in memory to compute the
     * idempotency payload fingerprint. A keyed request whose body exceeds this
     * is rejected with {@code 413 Payload Too Large} before any store work — the
     * body cannot be hashed, so the idempotency guarantee the client asked for
     * (by sending the key) cannot be honoured.
     *
     * <p>Only <strong>keyed</strong> requests are buffered, so this never
     * affects large unkeyed uploads (those are governed by your container /
     * codec limits, which this property does not touch). The check bounds the
     * actual bytes read, so it holds for chunked bodies and spoofed
     * {@code Content-Length} alike.
     *
     * <p>Default {@code 1MB} (binary, = 1 MiB). Set to a non-positive value
     * (e.g. {@code -1}) to disable the cap and restore unbounded buffering.
     * Peak buffer memory is roughly {@code max-body-size × concurrent keyed
     * requests}, so lower it on high-thread deployments and raise it
     * deliberately for large-JSON APIs.
     *
     * <p>This is independent of {@code failure-strategy}: that governs store
     * outages (where fail-open may proceed untracked); an over-cap body is a
     * deterministic client error and is always rejected.
     */
    private DataSize maxBodySize = DataSize.ofMegabytes(1);

    /**
     * Maximum response body the filter will buffer in memory to snapshot into
     * the store. A keyed request whose handler streams a larger response is
     * <strong>not cached</strong> (the lock is released, the same key retried
     * cleanly) — but the client still receives the full response unchanged,
     * because the cap only bounds the in-memory copy, never the bytes on the
     * wire. This is the symmetric twin of {@link #getMaxBodySize() max-body-size}
     * for the response side.
     *
     * <p>Unlike the request cap (which can reject with {@code 413} before the
     * handler runs), an oversized response cannot be rejected — its bytes are
     * already being streamed to the client when the limit trips — so it is
     * silently dropped from the cache and logged at {@code WARN}. Be aware this
     * means a retry <em>re-executes</em> the handler instead of replaying; set
     * the cap above any response your side-effecting endpoints legitimately
     * return.
     *
     * <p>Default {@code 1MB} (binary). Set to a non-positive value (e.g.
     * {@code -1}) to disable the response cap and restore unbounded buffering.
     *
     * <p>Peak per-request capture memory is up to ~2× this (a growing
     * {@code ByteArrayOutputStream} doubles its backing array), and a keyed
     * request holds the request-body copy ({@code max-body-size}) at the same
     * time, so budget roughly {@code max-body-size + 2 × max-response-size} per
     * concurrent keyed request. Response <em>headers</em> are not bounded by
     * this property.
     */
    private DataSize maxResponseSize = DataSize.ofMegabytes(1);

    /**
     * HTTP status codes that, when returned by the downstream handler, are
     * <strong>not</strong> persisted as the idempotency record: the lock is
     * released instead of saved, so the very same {@code Idempotency-Key} can be
     * reused on a corrected retry rather than being pinned to the failure for
     * the full TTL.
     *
     * <p>Default <strong>empty</strong> — every handler response (2xx/3xx/4xx)
     * is cached as before, so this changes nothing unless you opt in. A common
     * starting set is {@code [400, 401, 403, 429]}: a malformed request (400),
     * an unauthenticated/forbidden one (401/403) or a rate-limited one (429)
     * never committed the operation, so replaying that failure on retry is
     * unhelpful. Leave committed business outcomes — {@code 402} card-declined,
     * {@code 404} not-found, {@code 409} conflict, {@code 422} unprocessable —
     * out of the set so they keep replaying.
     *
     * <p>This only governs the handler's own responses. The starter's own
     * rejections (invalid-key 400, lock-held 409, payload-mismatch 422,
     * principal-required 422, payload-too-large 413) are written before the
     * handler runs and were never cached, so listing those codes here has no
     * effect on them. A 5xx <em>not</em> listed here stays controlled by
     * {@code cache-5xx}; listing a specific 5xx opts it out even when
     * {@code cache-5xx} is {@code true}.
     *
     * <p>Note: a released key carries no stored payload hash, so a retry with a
     * <em>different</em> (corrected) body will not trip the payload-mismatch
     * 422. This is a deliberate convenience and differs from Stripe, which
     * caches executed errors and expects a fresh key after correcting input.
     */
    private Set<Integer> nonCacheableStatuses = new LinkedHashSet<>();

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
    public Observations getObservations() { return observations; }
    public Health getHealth() { return health; }
    public LockExtension getLockExtension() { return lockExtension; }
    public Jdbc getJdbc() { return jdbc; }
    public Redis getRedis() { return redis; }
    public boolean isCache5xx() { return cache5xx; }
    public void setCache5xx(boolean cache5xx) { this.cache5xx = cache5xx; }
    public PrincipalBinding getPrincipalBinding() { return principalBinding; }
    public void setPrincipalBinding(PrincipalBinding principalBinding) { this.principalBinding = principalBinding; }
    public DataSize getMaxBodySize() { return maxBodySize; }
    public void setMaxBodySize(DataSize maxBodySize) { this.maxBodySize = maxBodySize; }
    public DataSize getMaxResponseSize() { return maxResponseSize; }
    public void setMaxResponseSize(DataSize maxResponseSize) { this.maxResponseSize = maxResponseSize; }
    public Set<Integer> getNonCacheableStatuses() { return nonCacheableStatuses; }
    public void setNonCacheableStatuses(Set<Integer> nonCacheableStatuses) { this.nonCacheableStatuses = nonCacheableStatuses; }

    /**
     * Single decision point for whether a handler response with the given
     * status should be persisted as the idempotency record. Folds the two
     * opt-outs: 5xx responses when {@link #isCache5xx() cache-5xx} is off, and
     * any status listed in {@link #getNonCacheableStatuses() non-cacheable-statuses}.
     * A {@code false} result means the lock is released instead of saved.
     */
    public boolean shouldCache(int status) {
        if (!cache5xx && status >= 500 && status < 600) {
            return false;
        }
        return !nonCacheableStatuses.contains(status);
    }

    /**
     * The {@link #getMaxBodySize() max body size} resolved to an {@code int}
     * byte count for the buffering paths, or {@code -1} when buffering is
     * unbounded. A configured size at or above {@link Integer#MAX_VALUE} is
     * treated as unbounded: a single {@code byte[]} cannot hold ~2 GiB, so the
     * cap would be unenforceable anyway.
     *
     * <p>This {@code < Integer.MAX_VALUE} guarantee is what lets the servlet
     * wrapper read {@code maxBytes + 1} without risking an integer overflow to
     * a negative {@code readNBytes} argument.
     */
    public int effectiveMaxBodyBytes() {
        return clampToInt(maxBodySize);
    }

    /**
     * The {@link #getMaxResponseSize() max response size} resolved to an
     * {@code int} byte ceiling for the capture buffer, or {@code -1} when
     * unbounded. Same {@code >= Integer.MAX_VALUE → unbounded} clamp as
     * {@link #effectiveMaxBodyBytes()}.
     */
    public int effectiveMaxResponseBytes() {
        return clampToInt(maxResponseSize);
    }

    private static int clampToInt(DataSize size) {
        long bytes = size == null ? -1L : size.toBytes();
        if (bytes <= 0 || bytes >= Integer.MAX_VALUE) {
            return -1;
        }
        return (int) bytes;
    }

    public enum Backend { AUTO, JDBC, REDIS, IN_MEMORY }
    public enum FailureStrategy { FAIL_OPEN, FAIL_CLOSED }
    public enum PayloadValidation { ENABLED, DISABLED }
    public enum PrincipalBinding { AUTO, DISABLED, REQUIRED }

    /**
     * Observation (tracing + metrics) knobs. Instrumentation runs through the
     * Micrometer Observation API, so it emits both a span (when a tracer —
     * OpenTelemetry or Brave — is wired) and an {@code idempotency} timer/counter
     * tagged by outcome (when a meter registry is wired). With neither, the
     * registry is {@code NOOP} and this is free.
     */
    public static class Observations {
        /**
         * Master switch for idempotency observations. Default {@code true}; it
         * is already a no-op without an {@code ObservationRegistry} bean, so this
         * only matters to hard-disable instrumentation even when a tracer/meter
         * registry is present.
         */
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /**
     * Actuator health-indicator knobs. The indicator only registers when Spring
     * Boot Actuator is on the classpath and
     * {@code management.health.idempotency.enabled} is not {@code false} (the
     * standard Actuator switch); these settings tune its behaviour once active.
     */
    public static class Health {
        /**
         * How long a probe result is cached before the store is re-probed. The
         * health endpoint is polled by several probers (Kubernetes, load
         * balancer, Prometheus) on a short interval; caching bounds backend hits
         * to one per interval and damps flapping. Default {@code 1s}. Set to
         * {@code 0} (or negative) to probe on every call.
         */
        private Duration cacheTtl = Duration.ofSeconds(1);

        public Duration getCacheTtl() { return cacheTtl; }
        public void setCacheTtl(Duration cacheTtl) { this.cacheTtl = cacheTtl; }
    }

    /**
     * Lock-extension (heartbeat) knobs. When {@code enabled}, a background
     * heartbeat renews the in-flight lock of a running handler so it cannot
     * expire mid-flight and be stolen by a concurrent retry (which would re-run
     * the operation). Off by default: it adds a background scheduler and store
     * load, and it <strong>narrows but does not close</strong> the
     * duplicate-execution window (a stop-the-world pause or store partition
     * longer than {@code lock-timeout} still lapses the lease). Only the JDBC,
     * Redis and in-memory stores support extension; against a store that does
     * not, the heartbeat is disabled with a startup warning.
     */
    public static class LockExtension {
        /**
         * Master switch for the heartbeat. Default {@code false} — opt in only for
         * endpoints whose handlers can legitimately run longer than
         * {@code lock-timeout}, and design those handlers to tolerate the residual
         * steal window regardless (see the README).
         */
        private boolean enabled = false;

        /**
         * How often the lock is renewed. When unset, defaults to
         * {@code lock-timeout / 3} — renewing at a third of the window tolerates a
         * missed beat before the lock would expire. Each renewal slides the lock
         * forward by a full {@code lock-timeout}.
         */
        private Duration interval;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) { this.interval = interval; }
    }

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
