package io.github.lu1tr0n.idempotency.reactive;

import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyProperties;
import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyProperties.PrincipalBinding;
import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.core.PayloadHasher;
import io.github.lu1tr0n.idempotency.core.ResponseTtlDirective;
import io.github.lu1tr0n.idempotency.heartbeat.LockHeartbeat;
import io.github.lu1tr0n.idempotency.exception.IdempotencyKeyTooLongException;
import io.github.lu1tr0n.idempotency.exception.IdempotencyPrincipalRequiredException;
import io.github.lu1tr0n.idempotency.principal.PrincipalKeyComposer;
import io.github.lu1tr0n.idempotency.principal.ReactiveIdempotencyPrincipalResolver;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reactive equivalent of {@code IdempotencyFilter} for Spring WebFlux.
 *
 * <p>The semantics match the servlet filter as closely as the reactive
 * runtime allows: same {@code IdempotencyStore} SPI, same key resolution
 * (header → key), same payload-mismatch 422, same concurrent-lock 409,
 * same {@code Idempotency-Replayed} header on replays.
 *
 * <p>The only inherent difference: the request body has to be materialised
 * into a byte array up-front so the SHA-256 fingerprint can be computed
 * and the deserialised downstream still sees a fresh stream. The {@code
 * CachedBodyServerHttpRequestDecorator} below does exactly that — the
 * full payload is buffered in memory before the chain proceeds.
 */
public class IdempotencyWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyWebFilter.class);
    private static final String REPLAYED_HEADER = "Idempotency-Replayed";

    /**
     * Spring Security's {@code WebFilterChainProxy} runs at
     * {@code SecurityProperties.DEFAULT_FILTER_ORDER} (-100) and populates the
     * reactive {@code SecurityContext}. When principal binding is active this
     * filter must run after it, so the principal is available when we scope the
     * key. When binding is off we keep running early so replays short-circuit
     * before any downstream work.
     */
    private static final int AFTER_SECURITY_ORDER = -99;
    private static final int BEFORE_SECURITY_ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final Set<String> trackedMethods;
    private final ReactiveIdempotencyPrincipalResolver principalResolver;
    private final LockHeartbeat heartbeat;
    private final int maxBodyBytes;
    private final int maxResponseBytes;
    /** Control-header name when the per-response TTL override is enabled, else {@code null}. */
    private final String responseTtlHeaderName;
    private final long responseTtlMaxSeconds;

    public IdempotencyWebFilter(IdempotencyStore store, IdempotencyProperties properties) {
        this(store, properties, null);
    }

    public IdempotencyWebFilter(IdempotencyStore store, IdempotencyProperties properties,
                                ReactiveIdempotencyPrincipalResolver principalResolver) {
        this(store, properties, principalResolver, LockHeartbeat.NOOP);
    }

    public IdempotencyWebFilter(IdempotencyStore store, IdempotencyProperties properties,
                                ReactiveIdempotencyPrincipalResolver principalResolver,
                                LockHeartbeat heartbeat) {
        this.store = store;
        this.properties = properties;
        this.principalResolver = principalResolver;
        this.heartbeat = heartbeat;
        this.trackedMethods = Set.copyOf(properties.getMethods().stream()
            .map(m -> m.toUpperCase(Locale.ROOT))
            .toList());
        this.maxBodyBytes = properties.effectiveMaxBodyBytes();
        this.maxResponseBytes = properties.effectiveMaxResponseBytes();
        this.responseTtlHeaderName = properties.getResponseTtlHeader().isEnabled()
            ? properties.getResponseTtlHeader().getName()
            : null;
        this.responseTtlMaxSeconds = properties.effectiveResponseTtlMaxSeconds();
    }

    private boolean principalBindingActive() {
        return principalResolver != null && properties.getPrincipalBinding() != PrincipalBinding.DISABLED;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!properties.isEnabled() || !trackedMethods.contains(request.getMethod().name())) {
            return chain.filter(exchange);
        }

        String rawKey = request.getHeaders().getFirst(properties.getHeaderName());
        if (rawKey == null || rawKey.isBlank()) {
            // No key — same semantics as the servlet filter: untracked.
            return chain.filter(exchange);
        }

        IdempotencyKey key;
        try {
            key = IdempotencyKey.of(rawKey);
        } catch (IllegalArgumentException ex) {
            return writeJsonError(exchange.getResponse(), HttpStatus.BAD_REQUEST,
                "INVALID_IDEMPOTENCY_KEY", ex.getMessage());
        }

        return scopeKey(key)
            .flatMap(scopedKey -> materialiseBody(request)
                .flatMap(bodyBytes -> applyIdempotency(exchange, chain, scopedKey, bodyBytes)))
            .onErrorResume(IdempotencyPrincipalRequiredException.class,
                ex -> writeJsonError(exchange.getResponse(), HttpStatus.UNPROCESSABLE_ENTITY,
                    "IDEMPOTENCY_PRINCIPAL_REQUIRED", ex.getMessage()))
            .onErrorResume(IdempotencyKeyTooLongException.class,
                ex -> writeJsonError(exchange.getResponse(), HttpStatus.BAD_REQUEST,
                    "INVALID_IDEMPOTENCY_KEY", ex.getMessage()))
            // Body exceeded the cap while materialising for the fingerprint.
            // The limited join() already released the buffers it accumulated;
            // reject with 413 — never fall through to the fail-open untracked
            // path, which would defeat the cap.
            .onErrorResume(DataBufferLimitException.class,
                ex -> writeJsonError(exchange.getResponse(), HttpStatus.PAYLOAD_TOO_LARGE,
                    "IDEMPOTENCY_PAYLOAD_TOO_LARGE",
                    "Request body exceeds the configured idempotency limit of " + maxBodyBytes + " bytes."));
    }

    /**
     * Folds the authenticated principal into the key (IETF §5) without blocking.
     * When binding is off or no resolver is wired, returns the bare key — the
     * stored key is then byte-for-byte what it was before this feature. An
     * anonymous request yields the bare key in {@code auto} and a
     * {@link IdempotencyPrincipalRequiredException} in {@code required}.
     */
    private Mono<IdempotencyKey> scopeKey(IdempotencyKey rawKey) {
        if (!principalBindingActive()) {
            return Mono.just(rawKey);
        }
        boolean required = properties.getPrincipalBinding() == PrincipalBinding.REQUIRED;
        return principalResolver.resolvePrincipal()
            .map(principal -> PrincipalKeyComposer.compose(principal, rawKey))
            .switchIfEmpty(Mono.defer(() -> required
                ? Mono.error(new IdempotencyPrincipalRequiredException(
                    "principal-binding=required but the request supplied an Idempotency-Key "
                        + "without an authenticated principal to scope it to."))
                // auto + anonymous → anonymous namespace, disjoint from scoped.
                : Mono.just(PrincipalKeyComposer.anonymous(rawKey))));
    }

    private Mono<Void> applyIdempotency(ServerWebExchange exchange, WebFilterChain chain,
                                        IdempotencyKey key, byte[] bodyBytes) {
        ServerHttpRequest original = exchange.getRequest();
        String payloadHash = PayloadHasher.hash(original.getMethod().name(),
            original.getURI().getRawPath(), bodyBytes);

        Optional<IdempotencyRecord> existing;
        try {
            existing = store.findRecord(key);
        } catch (IdempotencyStore.StoreException ex) {
            return handleStoreFailure("findRecord", ex, exchange, chain, bodyBytes);
        }

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (payloadValidationEnabled() && record.payloadHash() != null
                && !record.payloadHash().equals(payloadHash)) {
                return writeJsonError(exchange.getResponse(), HttpStatus.UNPROCESSABLE_ENTITY,
                    "IDEMPOTENCY_KEY_MISMATCH",
                    "The supplied Idempotency-Key was previously used with a different request payload.");
            }
            return replay(exchange.getResponse(), record);
        }

        Optional<IdempotencyStore.LockToken> lock;
        try {
            lock = store.acquireLock(key, properties.getLockTimeout());
        } catch (IdempotencyStore.StoreException ex) {
            return handleStoreFailure("acquireLock", ex, exchange, chain, bodyBytes);
        }
        if (lock.isEmpty()) {
            ServerHttpResponse response = exchange.getResponse();
            response.getHeaders().set("Retry-After", "1");
            return writeJsonError(response, HttpStatus.CONFLICT, "IDEMPOTENCY_LOCK_HELD",
                "Another in-flight request holds the lock for this Idempotency-Key. Retry after a short backoff.");
        }

        IdempotencyStore.LockToken token = lock.get();
        // Renew the lock while the handler runs (no-op unless lock-extension is
        // enabled). doFinally stops it on complete, error AND cancel — the cancel
        // (client disconnect) path is why it must be doFinally: doOnSuccess /
        // doOnError don't fire on cancel, so without this a disconnected request
        // would have its lock renewed forever.
        LockHeartbeat.Handle heartbeatHandle = heartbeat.start(key, token);
        CapturingResponseDecorator capturing =
            new CapturingResponseDecorator(exchange.getResponse(), maxResponseBytes, responseTtlHeaderName);
        ServerHttpRequest cachedRequest = new CachedBodyServerHttpRequestDecorator(original, bodyBytes);
        ServerWebExchange mutated = exchange.mutate().request(cachedRequest).response(capturing).build();

        return chain.filter(mutated)
            .doOnSuccess(ignored -> persistOrRelease(key, token, payloadHash, capturing))
            .doOnError(ex -> safeReleaseLock(key, token))
            .doFinally(signal -> heartbeatHandle.stop());
    }

    private void persistOrRelease(IdempotencyKey key, IdempotencyStore.LockToken token,
                                  String payloadHash, CapturingResponseDecorator capturing) {
        int status = capturing.statusValue();
        if (!properties.shouldCache(status)) {
            log.debug("WebFlux idempotency skip cache (key={}, status={})", key.value(), status);
            safeReleaseLock(key, token);
            return;
        }
        if (capturing.isOverCap()) {
            log.warn("WebFlux response exceeded max-response-size (key={}, status={}); not caching, releasing lock. "
                    + "A retry will re-execute — raise spring.idempotency.max-response-size for large-response endpoints.",
                key.value(), status);
            safeReleaseLock(key, token);
            return;
        }
        try {
            byte[] body = capturing.capturedBody();
            Instant now = Instant.now();
            IdempotencyRecord.Builder b = IdempotencyRecord.builder()
                .key(key.value())
                .payloadHash(payloadValidationEnabled() ? payloadHash : null)
                .statusCode(status)
                .body(body)
                .contentType(capturing.contentTypeValue())
                .createdAt(now)
                .expiresAt(resolveExpiry(now, key, capturing));
            HttpHeaders headers = capturing.getHeaders();
            for (String name : headers.keySet()) {
                // The control header was already stripped in beforeCommit; skip
                // it defensively so a directive can never be stored and replayed.
                if (responseTtlHeaderName != null && responseTtlHeaderName.equalsIgnoreCase(name)) {
                    continue;
                }
                for (String value : headers.get(name)) {
                    b.addHeader(name, value);
                }
            }
            store.save(b.build(), token);
        } catch (IdempotencyStore.StoreException ex) {
            log.warn("WebFlux idempotency save failed (key={}): {}", key.value(), ex.getMessage());
        }
    }

    /**
     * Resolve the record's expiry: the per-response TTL override when enabled and
     * the handler emitted a valid directive (read + stripped in {@code
     * beforeCommit}), otherwise the global {@code ttl}.
     */
    private Instant resolveExpiry(Instant now, IdempotencyKey key, CapturingResponseDecorator capturing) {
        if (responseTtlHeaderName != null) {
            long seconds = ResponseTtlDirective.resolveSeconds(capturing.controlHeaderValues(), responseTtlMaxSeconds);
            if (seconds >= 0) {
                log.debug("WebFlux idempotency per-response TTL override (key={}): persisting record for {}s.",
                    key.value(), seconds);
                return now.plusSeconds(seconds);
            }
        }
        return now.plus(properties.getTtl());
    }

    private void safeReleaseLock(IdempotencyKey key, IdempotencyStore.LockToken token) {
        try {
            store.releaseLock(key, token);
        } catch (RuntimeException ignored) {
        }
    }

    private Mono<Void> replay(ServerHttpResponse response, IdempotencyRecord cached) {
        response.setRawStatusCode(cached.statusCode());
        if (cached.contentType() != null) {
            response.getHeaders().setContentType(MediaType.parseMediaType(cached.contentType()));
        }
        for (var entry : cached.headers().entrySet()) {
            String name = entry.getKey();
            if ("Content-Length".equalsIgnoreCase(name) || "Transfer-Encoding".equalsIgnoreCase(name)) {
                continue;
            }
            response.getHeaders().put(name, entry.getValue());
        }
        response.getHeaders().set(REPLAYED_HEADER, "true");
        byte[] body = cached.body();
        response.getHeaders().setContentLength(body == null ? 0 : body.length);
        DataBuffer buffer = response.bufferFactory().wrap(body == null ? new byte[0] : body);
        return response.writeWith(Mono.just(buffer));
    }

    private Mono<byte[]> materialiseBody(ServerHttpRequest request) {
        // The limited join emits DataBufferLimitException (and releases the
        // buffers it accumulated) once the body passes maxBodyBytes. With the
        // cap disabled (-1) fall back to the unbounded join.
        Mono<DataBuffer> joined = maxBodyBytes > 0
            ? DataBufferUtils.join(request.getBody(), maxBodyBytes)
            : DataBufferUtils.join(request.getBody());
        return joined
            .map(buffer -> {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                DataBufferUtils.release(buffer);
                return bytes;
            })
            .defaultIfEmpty(new byte[0]);
    }

    /**
     * Store-outage handling, mirroring the servlet filter: {@code fail-open}
     * proceeds without an idempotency guarantee, {@code fail-closed} (the
     * default) refuses the request with 503 rather than risk a duplicate
     * operation. Reactive parity for {@code spring.idempotency.failure-strategy}.
     */
    private Mono<Void> handleStoreFailure(String operation, IdempotencyStore.StoreException ex,
                                          ServerWebExchange exchange, WebFilterChain chain, byte[] bodyBytes) {
        if (properties.getFailureStrategy() == IdempotencyProperties.FailureStrategy.FAIL_OPEN) {
            log.warn("WebFlux idempotency store unreachable during {}; falling back to fail-open "
                + "(no idempotency guarantee): {}", operation, ex.getMessage());
            return continueWithCachedBody(exchange, chain, bodyBytes);
        }
        log.error("WebFlux idempotency store unreachable during {}; refusing the request (fail-closed): {}",
            operation, ex.getMessage());
        return writeJsonError(exchange.getResponse(), HttpStatus.SERVICE_UNAVAILABLE,
            "IDEMPOTENCY_STORE_UNAVAILABLE",
            "Idempotency storage is currently unavailable; please retry shortly.");
    }

    private Mono<Void> continueWithCachedBody(ServerWebExchange exchange, WebFilterChain chain, byte[] bodyBytes) {
        ServerHttpRequest cached = new CachedBodyServerHttpRequestDecorator(exchange.getRequest(), bodyBytes);
        return chain.filter(exchange.mutate().request(cached).build());
    }

    private boolean payloadValidationEnabled() {
        return properties.getPayloadValidation() == IdempotencyProperties.PayloadValidation.ENABLED;
    }

    private Mono<Void> writeJsonError(ServerHttpResponse response, HttpStatus status, String code, String message) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message.replace("\"", "\\\"") + "\"}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.getHeaders().setContentLength(bytes.length);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // With principal binding active we must run AFTER Spring Security so the
        // reactive SecurityContext is populated; otherwise run early so replays
        // short-circuit before any downstream work.
        return principalBindingActive() ? AFTER_SECURITY_ORDER : BEFORE_SECURITY_ORDER;
    }

    // === Request decorator: replays the body materialised once. ===

    private static final class CachedBodyServerHttpRequestDecorator extends ServerHttpRequestDecorator {
        private final byte[] body;

        CachedBodyServerHttpRequestDecorator(ServerHttpRequest delegate, byte[] body) {
            super(delegate);
            this.body = body;
        }

        @Override
        public Flux<DataBuffer> getBody() {
            if (body.length == 0) return Flux.empty();
            return Flux.defer(() -> Flux.just(new DefaultDataBufferFactory().wrap(ByteBuffer.wrap(body))));
        }
    }

    // === Response decorator: captures the body for store snapshot. ===

    private static final class CapturingResponseDecorator extends ServerHttpResponseDecorator {
        private java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(1024);
        private final int maxResponseBytes;
        private long capturedBytes = 0;
        private boolean overCap = false;

        /**
         * Per-response TTL control header to read+strip, or {@code null} when off.
         * The values are captured in {@code beforeCommit} (which fires on the
         * body, empty-body and flush commit paths alike, before headers go
         * read-only) and published via a {@code volatile} field so
         * {@code persistOrRelease} — which may run on a different thread — sees
         * them.
         */
        private final String controlHeaderName;
        private volatile List<String> controlHeaderValues = List.of();

        CapturingResponseDecorator(ServerHttpResponse delegate, int maxResponseBytes) {
            this(delegate, maxResponseBytes, null);
        }

        CapturingResponseDecorator(ServerHttpResponse delegate, int maxResponseBytes, String controlHeaderName) {
            super(delegate);
            this.maxResponseBytes = maxResponseBytes;
            this.controlHeaderName = controlHeaderName;
            if (controlHeaderName != null) {
                beforeCommit(() -> {
                    List<String> values = getHeaders().get(controlHeaderName);
                    if (values != null && !values.isEmpty()) {
                        controlHeaderValues = new ArrayList<>(values);
                        getHeaders().remove(controlHeaderName);
                    }
                    return Mono.empty();
                });
            }
        }

        List<String> controlHeaderValues() {
            return controlHeaderValues;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return super.writeWith(Flux.from(body).map(this::tee));
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return super.writeAndFlushWith(Flux.from(body)
                .map(inner -> Flux.from(inner).map(this::tee)));
        }

        private DataBuffer tee(DataBuffer dataBuffer) {
            try {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                // Always copy + forward to the client; only the capture write is
                // gated, and we never branch the buffer handling (one release
                // regime: the source is released in finally, the copy goes
                // downstream). This keeps the client stream byte-identical.
                capture(bytes);
                return getDelegate().bufferFactory().wrap(bytes);
            } finally {
                DataBufferUtils.release(dataBuffer);
            }
        }

        private void capture(byte[] bytes) {
            if (overCap) {
                return;
            }
            if (maxResponseBytes < 0) {
                buffer.write(bytes, 0, bytes.length);
                return;
            }
            if (capturedBytes + bytes.length > maxResponseBytes) {
                overCap = true;
                buffer = null; // drop the partial snapshot so it is GC'd
                return;
            }
            buffer.write(bytes, 0, bytes.length);
            capturedBytes += bytes.length;
        }

        boolean isOverCap() {
            return overCap;
        }

        byte[] capturedBody() {
            if (overCap || buffer == null) {
                throw new IllegalStateException(
                    "capturedBody() called on an over-cap response; gate on isOverCap() and do not cache it.");
            }
            return buffer.toByteArray();
        }

        int statusValue() {
            Integer code = getRawStatusCode();
            return code == null ? HttpStatus.OK.value() : code;
        }

        String contentTypeValue() {
            MediaType ct = getHeaders().getContentType();
            return ct == null ? null : ct.toString();
        }
    }
}
