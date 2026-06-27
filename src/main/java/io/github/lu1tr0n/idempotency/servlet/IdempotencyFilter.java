package io.github.lu1tr0n.idempotency.servlet;

import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyProperties;
import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyKeyResolver;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.core.PayloadHasher;

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
 * <h2>What we deliberately do not do (yet)</h2>
 *
 * <ul>
 *   <li>5xx response handling — currently 5xx responses are cached just like
 *       2xx. A future toggle ({@code spring.idempotency.cache-5xx=false})
 *       will let consumers opt out so that transient downstream failures
 *       don't poison the cache.</li>
 *   <li>Streaming / chunked responses — the captured <em>response</em> body is
 *       buffered in memory, fine for typical JSON API responses but not for
 *       large downloads. A response-size cap is still TODO (the
 *       {@code spring.idempotency.max-body-size} property bounds the
 *       <em>request</em> body only).</li>
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

    public IdempotencyFilter(IdempotencyStore store,
                             IdempotencyKeyResolver keyResolver,
                             IdempotencyProperties properties) {
        this.store = store;
        this.keyResolver = keyResolver;
        this.properties = properties;
        // Pre-normalise once instead of upper-casing per request.
        this.trackedMethods = Set.copyOf(properties.getMethods().stream()
            .map(m -> m.toUpperCase(Locale.ROOT))
            .toList());
        // Resolve the body ceiling once (-1 = unbounded).
        this.maxBodyBytes = properties.effectiveMaxBodyBytes();
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
                return;
            }
            replay(cached, response);
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
            return;
        }

        // Execute the chain and snapshot the response.
        IdempotencyStore.LockToken token = lock.get();
        CapturingHttpServletResponseWrapper capturing = new CapturingHttpServletResponseWrapper(response);
        boolean chainThrew = false;
        try {
            chain.doFilter(cachedRequest, capturing);
        } catch (RuntimeException | IOException | ServletException ex) {
            chainThrew = true;
            store.releaseLock(key, token);
            throw rethrow(ex);
        }

        if (chainThrew) return; // unreachable, but keeps the compiler honest

        // 5xx-cache opt-out: when disabled, a downstream blip (502 from an
        // upstream, 503 during a deploy) does not poison the cache for the
        // full TTL. The lock is released instead of the record being saved
        // so the next retry can re-attempt the operation cleanly.
        if (!properties.isCache5xx() && capturing.capturedStatus() >= 500 && capturing.capturedStatus() < 600) {
            log.debug("Skipping idempotency cache for 5xx response (key={}, status={}); releasing lock.",
                key.value(), capturing.capturedStatus());
            try {
                store.releaseLock(key, token);
            } catch (IdempotencyStore.StoreException releaseFailure) {
                log.warn("Failed to release lock after 5xx skip (key={}). Lock will expire after {}.",
                    key.value(), properties.getLockTimeout(), releaseFailure);
            }
            return;
        }

        // Persist the captured response. This implicitly releases the lock.
        try {
            IdempotencyRecord record = snapshot(key, payloadHash, capturing);
            store.save(record, token);
        } catch (IdempotencyStore.StoreException storeFailure) {
            // The user has already seen the response — the chain wrote to the
            // wrapped response which tees to the real one. We can no longer
            // fail-closed: the caller already got their answer. Log and move
            // on; the lock will expire on its own.
            log.warn("Idempotency record save failed after successful response (key={}). Lock will expire after {}.",
                key.value(), properties.getLockTimeout(), storeFailure);
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
        IdempotencyRecord.Builder b = IdempotencyRecord.builder()
            .key(key.value())
            .payloadHash(payloadValidationEnabled() ? payloadHash : null)
            .statusCode(response.capturedStatus())
            .body(body)
            .contentType(response.getContentType())
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(properties.getTtl()));

        for (String name : response.getHeaderNames()) {
            for (String value : response.getHeaders(name)) {
                b.addHeader(name, value);
            }
        }
        return b.build();
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
