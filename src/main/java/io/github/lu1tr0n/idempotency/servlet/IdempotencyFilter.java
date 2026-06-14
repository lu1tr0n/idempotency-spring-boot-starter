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
import java.nio.charset.StandardCharsets;
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
 *   <li>Streaming / chunked responses — the captured body is buffered in
 *       memory, fine for typical JSON API responses but not for large
 *       downloads. The auto-config sets a 1 MiB safety limit (TODO).</li>
 * </ul>
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private final IdempotencyStore store;
    private final IdempotencyKeyResolver keyResolver;
    private final IdempotencyProperties properties;
    private final Set<String> trackedMethods;

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
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) return true;
        return !trackedMethods.contains(request.getMethod().toUpperCase(Locale.ROOT));
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
        }

        // Wrap request so the body can be read twice (hash now, controller later).
        // Spring's ContentCachingRequestWrapper is unsuitable here — it tees
        // bytes as the consumer reads, so reading the body for hashing would
        // leave the chain with an empty stream. Our wrapper snapshots the
        // body up-front and serves a fresh stream on every getInputStream().
        CachedBodyHttpServletRequestWrapper cachedRequest = (request instanceof CachedBodyHttpServletRequestWrapper c)
            ? c
            : new CachedBodyHttpServletRequestWrapper(request);

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
        response.setStatus(cached.statusCode());
        if (cached.contentType() != null) {
            response.setContentType(cached.contentType());
        }
        for (Map.Entry<String, List<String>> entry : cached.headers().entrySet()) {
            for (String value : entry.getValue()) {
                response.addHeader(entry.getKey(), value);
            }
        }
        response.setHeader(REPLAYED_HEADER, "true");
        response.getOutputStream().write(cached.body());
        response.getOutputStream().flush();
    }

    private IdempotencyRecord snapshot(IdempotencyKey key, String payloadHash, CapturingHttpServletResponseWrapper response) {
        IdempotencyRecord.Builder b = IdempotencyRecord.builder()
            .key(key.value())
            .payloadHash(payloadValidationEnabled() ? payloadHash : null)
            .statusCode(response.capturedStatus())
            .body(response.capturedBody())
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
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + escapeJson(message) + "\"}}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
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
