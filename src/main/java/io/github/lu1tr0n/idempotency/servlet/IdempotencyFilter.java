package io.github.lu1tr0n.idempotency.servlet;

import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyProperties;
import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.core.PayloadHasher;
import io.github.lu1tr0n.idempotency.core.ResponseTtlDirective;
import io.github.lu1tr0n.idempotency.heartbeat.LockHeartbeat;
import io.github.lu1tr0n.idempotency.observability.IdempotencyObservations;
import io.github.lu1tr0n.idempotency.observability.IdempotencyObservations.Outcome;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The heart of the starter: intercepts mutating HTTP requests, resolves the
 * idempotency key from the request, and orchestrates replay / lock / store
 * via the configured {@link IdempotencyStore}.
 *
 * <h2>Order of operations per request</h2>
 *
 * <ol>
 *   <li><strong>Scope check.</strong> Skip if the HTTP method is not in
 *       {@code spring.idempotency.methods} (defaults to POST/PUT/PATCH).</li>
 *   <li><strong>Key resolution.</strong> If no key header is present, skip
 *       — idempotency is always opt-in by the client.</li>
 *   <li><strong>Key validation.</strong> If the key fails
 *       {@link IdempotencyKey#of(String)} validation, return 400.</li>
 *   <li><strong>Payload hashing.</strong> Compute SHA-256 of method + URI +
 *       request body bytes (needs the request wrapped in
 *       {@link ContentCachingRequestWrapper} so we can read the body twice).</li>
 *   <li><strong>Cache lookup.</strong> If a record exists for the key:
 *       <ul>
 *         <li>Payload hash matches → replay the cached response and return.</li>
 *         <li>Payload hash differs → return 422 with
 *             {@code Idempotency-Mismatch: true}.</li>
 *       </ul></li>
 *   <li><strong>Lock acquisition.</strong> Try to take the lock. If held by
 *       another request, return 409 with {@code Retry-After: 1}.</li>
 *   <li><strong>Execute.</strong> Forward to the chain with a
 *       {@link CapturingHttpServletResponseWrapper} so we can snapshot the
 *       response.</li>
 *   <li><strong>Persist.</strong> If the chain succeeded (2xx/3xx/4xx),
 *       save the captured response; if the chain threw, release the lock so
 *       retries can proceed.</li>
 * </ol>
 *
 * <h2>Cache-control knobs</h2>
 *
 * <ul>
 *   <li>5xx responses are cached by default; set
 *       {@code spring.idempotency.cache-5xx=false} so transient downstream
 *       failures don't poison the cache. Arbitrary statuses can be excluded via
 *       {@code spring.idempotency.non-cacheable-statuses}.</li>
 *   <li>Both the request and the captured response body are buffered in memory
 *       (fine for typical JSON APIs, not large downloads). The
 *       {@code spring.idempotency.max-body-size} and
 *       {@code spring.idempotency.max-response-size} caps bound that buffering;
 *       an over-cap response streams to the client in full but is not cached.</li>
 * </ul>
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private final IdempotencyStore store;
    private final IdempotencyKeyResolver keyResolver;
    private final IdempotencyProperties properties;
    private final Set<String> trackedMethods;
    private final int maxBodyBytes;
    private final int maxResponseBytes;
    private final IdempotencyObservations observations;
    private final LockHeartbeat heartbeat;
    /** Control-header name when the per-response TTL override is enabled, else {@code null}. */
    private final String responseTtlHeaderName;
    private final long responseTtlMaxSeconds;

    public IdempotencyFilter(IdempotencyStore store,
                             IdempotencyKeyResolver keyResolver,
                             IdempotencyProperties properties) {
        this(store, keyResolver, properties, IdempotencyObservations.noop(), LockHeartbeat.NOOP);
    }

    public IdempotencyFilter(IdempotencyStore store,
                             IdempotencyKeyResolver keyResolver,
                             IdempotencyProperties properties,
                             IdempotencyObservations observations) {
        this(store, keyResolver, properties, observations, LockHeartbeat.NOOP);
    }

    public IdempotencyFilter(IdempotencyStore store,
                             IdempotencyKeyResolver keyResolver,
                             IdempotencyProperties properties,
                             IdempotencyObservations observations,
                             LockHeartbeat heartbeat) {
        this.store = store;
        this.keyResolver = keyResolver;
        this.properties = properties;
        this.observations = observations;
        this.heartbeat = heartbeat;
        // Pre-normalise once instead of upper-casing per request.
        this.trackedMethods = Set.copyOf(properties.getMethods().stream()
            .map(m -> m.toUpperCase(Locale.ROOT))
            .toList());
        // Resolve the body ceilings once (-1 = unbounded).
        this.maxBodyBytes = properties.effectiveMaxBodyBytes();
        this.maxResponseBytes = properties.effectiveMaxResponseBytes();
        // Per-response TTL override (null name = feature off).
        this.responseTtlHeaderName = properties.getResponseTtlHeader().isEnabled()
            ? properties.getResponseTtlHeader().getName()
            : null;
        this.responseTtlMaxSeconds = properties.effectiveResponseTtlMaxSeconds();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) return true;
        return !trackedMethods.contains(request.getMethod().toUpperCase(Locale.ROOT));
    }

    /**
     * Skip the filter on internal {@code /error} dispatches. Without this, when
     * a controller throws and Spring Boot's ErrorPageFilter forwards to
     * {@code /error}, our filter would try to re-acquire the lock for the same
     * idempotency key and return 409 — even though the original chain still
     * holds the lock and is about to save the (error) response.
     *
     * <p>Setting this to {@code true} (the OncePerRequestFilter default for
     * error dispatches is already true, but we set it explicitly for clarity
     * and to document the contract) preserves a clean single-pass semantics:
     * the body that comes out of the {@code /error} forward is captured by the
     * same response wrapper the original dispatch installed, AND the wrapper's
     * status is what the {@code /error} handler set (typically 400/500), so
     * the cached snapshot contains the rendered error body.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // Resolve / validate key.
        IdempotencyKey key;
        try {
            Optional<IdempotencyKey> resolved = keyResolver.resolve(request);
            if (resolved.isEmpty()) {
                // No key header → request proceeds without idempotency tracking.
                chain.doFilter(request, response);
                return;
            }
            key = resolved.get();
        } catch (IllegalArgumentException malformedKey) {
            writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", malformedKey.getMessage());
            return;
        } catch (io.github.lu1tr0n.idempotency.exception.IdempotencyPrincipalRequiredException principalRequired) {
            // principal-binding=required, key present but no authenticated
            // principal to scope it to: the key is well-formed but the policy
            // can't be satisfied — 422, mirroring the payload-mismatch idiom.
            writeJsonError(response, 422, "IDEMPOTENCY_PRINCIPAL_REQUIRED", principalRequired.getMessage());
            return;
        }

        // Wrap request so the body can be read twice (hash now, controller later).
        // Spring's ContentCachingRequestWrapper is unsuitable here — it tees
        // bytes as the consumer reads, so reading the body for hashing would
        // leave the chain with an empty stream. Our wrapper snapshots the
        // body up-front and serves a fresh stream on every getInputStream().
        CachedBodyHttpServletRequestWrapper cachedRequest;
        try {
            // A request that already carries our wrapper was snapshotted (and so
            // already capped) on the pass that installed it — reuse it as-is.
            cachedRequest = (request instanceof CachedBodyHttpServletRequestWrapper c)
                ? c
                : new CachedBodyHttpServletRequestWrapper(request, maxBodyBytes);
        } catch (io.github.lu1tr0n.idempotency.exception.IdempotencyPayloadTooLargeException tooLarge) {
            // Over the cap: we cannot hash the body, so we cannot honour the
            // idempotency guarantee the client opted into by sending the key.
            // Reject before any store work — no record, no lock. This is a
            // client error, independent of the fail-open/closed store strategy.
            writeJsonError(response, 413, "IDEMPOTENCY_PAYLOAD_TOO_LARGE", tooLarge.getMessage());
            observations.record(Outcome.PAYLOAD_TOO_LARGE, 413);
            return;
        }

        byte[] bodyBytes = cachedRequest.cachedBody();
        String payloadHash = PayloadHasher.hash(request.getMethod(), request.getRequestURI(), bodyBytes);

        // Cache lookup with payload validation.
        Optional<IdempotencyRecord> existing;
        try {
            existing = store.findRecord(key);
        } catch (IdempotencyStore.StoreException storeFailure) {
            handleStoreFailure("findRecord", storeFailure, response, () -> chain.doFilter(cachedRequest, response));
            return;
        }

        if (existing.isPresent()) {
            IdempotencyRecord cached = existing.get();
            if (payloadValidationEnabled() && cached.payloadHash() != null
                && !cached.payloadHash().equals(payloadHash)) {
                log.warn("Idempotency-Key mismatch: key={} stored_hash={} incoming_hash={}",
                    key.value(), cached.payloadHash(), payloadHash);
                // Headers must be set BEFORE the body is written — once
                // the response is committed, setHeader is a no-op.
                response.setHeader("Idempotency-Mismatch", "true");
                writeJsonError(response, 422, "IDEMPOTENCY_KEY_MISMATCH",
                    "The supplied Idempotency-Key was previously used with a different request payload.");
                observations.record(Outcome.PAYLOAD_MISMATCH, 422);
                return;
            }
            replay(cached, response);
            observations.record(Outcome.REPLAYED, cached.statusCode());
            return;
        }

        // Acquire lock.
        Optional<IdempotencyStore.LockToken> lock;
        try {
            lock = store.acquireLock(key, properties.getLockTimeout());
        } catch (IdempotencyStore.StoreException storeFailure) {
            handleStoreFailure("acquireLock", storeFailure, response, () -> chain.doFilter(cachedRequest, response));
            return;
        }

        if (lock.isEmpty()) {
            response.setHeader("Retry-After", "1");
            writeJsonError(response, HttpServletResponse.SC_CONFLICT, "IDEMPOTENCY_LOCK_HELD",
                "Another in-flight request holds the lock for this Idempotency-Key. Retry after a short backoff.");
            observations.record(Outcome.LOCK_HELD, HttpServletResponse.SC_CONFLICT);
            return;
        }

        // Execute the chain and snapshot the response. The heartbeat (a no-op
        // unless lock-extension is enabled) renews the lock while the handler
        // runs; the finally stops it on every exit path — success, skip,
        // over-cap, or a thrown handler.
        IdempotencyStore.LockToken token = lock.get();
        LockHeartbeat.Handle heartbeatHandle = heartbeat.start(key, token);
        try {
            CapturingHttpServletResponseWrapper capturing =
                new CapturingHttpServletResponseWrapper(response, maxResponseBytes, responseTtlHeaderName);
            boolean chainThrew = false;
            try {
                chain.doFilter(cachedRequest, capturing);
            } catch (RuntimeException | IOException | ServletException ex) {
                chainThrew = true;
                store.releaseLock(key, token);
                throw rethrow(ex);
            }

            if (chainThrew) return; // unreachable, but keeps the compiler honest

            // Non-cacheable status: a 5xx blip when cache-5xx is off, or a status
            // listed in non-cacheable-statuses (e.g. a 400 the client can fix).
            // Release the lock instead of saving so the same key can be retried
            // cleanly rather than being pinned to the failure for the full TTL.
            int capturedStatus = capturing.capturedStatus();
            if (!properties.shouldCache(capturedStatus)) {
                log.debug("Skipping idempotency cache (key={}, status={}); releasing lock.",
                    key.value(), capturedStatus);
                releaseAfterSkip(key, token);
                observations.record(Outcome.EXECUTED_NOT_STORED, capturedStatus);
                return;
            }

            // Response exceeded max-response-size: the client already received the
            // full body (the wrapper tees unconditionally) but the snapshot was
            // abandoned to bound memory, so there is nothing to cache. Release the
            // lock — a retry re-executes. WARN, not debug: this silently disables
            // idempotency for the key, which operators should see.
            if (capturing.isOverCap()) {
                log.warn("Response exceeded max-response-size (key={}, status={}); not caching, releasing lock. "
                        + "A retry will re-execute the handler — raise spring.idempotency.max-response-size if this "
                        + "endpoint legitimately returns large responses.", key.value(), capturedStatus);
                releaseAfterSkip(key, token);
                observations.record(Outcome.EXECUTED_NOT_STORED, capturedStatus);
                return;
            }

            // Persist the captured response. This implicitly releases the lock.
            try {
                IdempotencyRecord record = snapshot(key, payloadHash, capturing);
                store.save(record, token);
                observations.record(Outcome.EXECUTED_STORED, capturedStatus);
            } catch (IdempotencyStore.StoreException storeFailure) {
                // The user has already seen the response — the chain wrote to the
                // wrapped response which tees to the real one. We can no longer
                // fail-closed: the caller already got their answer. Log and move
                // on; the lock will expire on its own.
                log.warn("Idempotency record save failed after successful response (key={}). Lock will expire after {}.",
                    key.value(), properties.getLockTimeout(), storeFailure);
                // The handler ran but the response could not be persisted.
                observations.record(Outcome.EXECUTED_NOT_STORED, capturedStatus);
            }
        } finally {
            heartbeatHandle.stop();
        }
    }

    /** Release the lock for a response we chose not to cache, warning if the release itself fails. */
    private void releaseAfterSkip(IdempotencyKey key, IdempotencyStore.LockToken token) {
        try {
            store.releaseLock(key, token);
        } catch (IdempotencyStore.StoreException releaseFailure) {
            log.warn("Failed to release lock after skipping cache (key={}). Lock will expire after {}.",
                key.value(), properties.getLockTimeout(), releaseFailure);
        }
    }

    private void replay(IdempotencyRecord cached, HttpServletResponse response) throws IOException {
        byte[] body = cached.body();

        // Reset any pending state (some upstream filters might have set
        // Transfer-Encoding: chunked on the response if they touched it).
        // reset() is safe before commit; it clears headers AND body buffer.
        if (!response.isCommitted()) {
            response.reset();
        }

        response.setStatus(cached.statusCode());
        if (cached.contentType() != null) {
            response.setContentType(cached.contentType());
        }
        // setContentLength BEFORE any header copy so Tomcat picks identity
        // encoding (not chunked). On 4xx + chunked, Tomcat closes the
        // connection before flushing the body — finding #4.
        response.setContentLengthLong(body.length);
        for (Map.Entry<String, List<String>> entry : cached.headers().entrySet()) {
            // Skip Content-Length / Transfer-Encoding from cached headers —
            // we set them explicitly above and don't want stale values from
            // the original chunked response to override our identity encoding.
            String name = entry.getKey();
            if ("Content-Length".equalsIgnoreCase(name) || "Transfer-Encoding".equalsIgnoreCase(name)) {
                continue;
            }
            for (String value : entry.getValue()) {
                response.addHeader(name, value);
            }
        }
        response.setHeader(REPLAYED_HEADER, "true");
        response.getOutputStream().write(body);
        response.getOutputStream().flush();
        if (!response.isCommitted()) {
            response.flushBuffer();
        }
    }

    private IdempotencyRecord snapshot(IdempotencyKey key, String payloadHash, CapturingHttpServletResponseWrapper response) {
        byte[] body = response.capturedBody();
        Instant now = Instant.now();
        IdempotencyRecord.Builder b = IdempotencyRecord.builder()
            .key(key.value())
            .payloadHash(payloadValidationEnabled() ? payloadHash : null)
            .statusCode(response.capturedStatus())
            .body(body)
            .contentType(response.getContentType())
            .createdAt(now)
            .expiresAt(resolveExpiry(now, key, response));

        for (String name : response.getHeaderNames()) {
            // The control header is swallowed by the wrapper and never reaches
            // getHeaderNames(); skip it defensively in case a custom subclass
            // surfaces it, so a directive can never be stored and replayed.
            if (responseTtlHeaderName != null && responseTtlHeaderName.equalsIgnoreCase(name)) {
                continue;
            }
            for (String value : response.getHeaders(name)) {
                b.addHeader(name, value);
            }
        }
        return b.build();
    }

    /**
     * Resolve the record's expiry: the per-response TTL override when enabled and
     * the handler emitted a valid directive, otherwise the global {@code ttl}.
     */
    private Instant resolveExpiry(Instant now, IdempotencyKey key, CapturingHttpServletResponseWrapper response) {
        if (responseTtlHeaderName != null) {
            long seconds = ResponseTtlDirective.resolveSeconds(response.controlHeaderValues(), responseTtlMaxSeconds);
            if (seconds >= 0) {
                log.debug("Idempotency per-response TTL override (key={}): persisting record for {}s.",
                    key.value(), seconds);
                return now.plusSeconds(seconds);
            }
        }
        return now.plus(properties.getTtl());
    }

    private boolean payloadValidationEnabled() {
        return properties.getPayloadValidation() == IdempotencyProperties.PayloadValidation.ENABLED;
    }

    private void handleStoreFailure(String operation,
                                    IdempotencyStore.StoreException storeFailure,
                                    HttpServletResponse response,
                                    IoRunnable failOpenProceed) throws IOException, ServletException {
        if (properties.getFailureStrategy() == IdempotencyProperties.FailureStrategy.FAIL_OPEN) {
            log.warn("Idempotency store failure during {}; falling back to fail-open (no idempotency guarantee).",
                operation, storeFailure);
            failOpenProceed.run();
            return;
        }
        log.error("Idempotency store failure during {}; refusing the request (fail-closed).", operation, storeFailure);
        writeJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "IDEMPOTENCY_STORE_UNAVAILABLE",
            "Idempotency storage is currently unavailable; please retry shortly.");
    }

    private void writeJsonError(HttpServletResponse response, int status, String code, String message) throws IOException {
        // Same rationale as replay(): the shared writer commits the response so
        // Spring Boot's ErrorPageFilter does not forward a 4xx/5xx to /error
        // (which would overwrite our structured error body with the default
        // error page).
        IdempotencyHttpErrors.write(response, status, code, message);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        throw (E) t;
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException, ServletException;
    }
}
