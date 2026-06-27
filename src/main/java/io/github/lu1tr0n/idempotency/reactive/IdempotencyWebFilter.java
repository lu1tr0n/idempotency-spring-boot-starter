package io.github.lu1tr0n.idempotency.reactive;

import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyProperties;
import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyProperties.PrincipalBinding;
import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.core.PayloadHasher;
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
    private final int maxBodyBytes;

    public IdempotencyWebFilter(IdempotencyStore store, IdempotencyProperties properties) {
        this(store, properties, null);
    }

    public IdempotencyWebFilter(IdempotencyStore store, IdempotencyProperties properties,
                                ReactiveIdempotencyPrincipalResolver principalResolver) {
        this.store = store;
        this.properties = properties;
        this.principalResolver = principalResolver;
        this.trackedMethods = Set.copyOf(properties.getMethods().stream()
            .map(m -> m.toUpperCase(Locale.ROOT))
            .toList());
        this.maxBodyBytes = properties.effectiveMaxBodyBytes();
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
            log.warn("WebFlux idempotency store unreachable on findRecord(key={}): {}", key.value(), ex.getMessage());
            return continueWithCachedBody(exchange, chain, bodyBytes);
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
            log.warn("WebFlux idempotency store unreachable on acquireLock(key={}): {}", key.value(), ex.getMessage());
            return continueWithCachedBody(exchange, chain, bodyBytes);
        }
        if (lock.isEmpty()) {
            ServerHttpResponse response = exchange.getResponse();
            response.getHeaders().set("Retry-After", "1");
            return writeJsonError(response, HttpStatus.CONFLICT, "IDEMPOTENCY_LOCK_HELD",
                "Another in-flight request holds the lock for this Idempotency-Key. Retry after a short backoff.");
        }

        IdempotencyStore.LockToken token = lock.get();
        CapturingResponseDecorator capturing = new CapturingResponseDecorator(exchange.getResponse());
        ServerHttpRequest cachedRequest = new CachedBodyServerHttpRequestDecorator(original, bodyBytes);
        ServerWebExchange mutated = exchange.mutate().request(cachedRequest).response(capturing).build();

        return chain.filter(mutated)
            .doOnSuccess(ignored -> persistOrRelease(key, token, payloadHash, capturing))
            .doOnError(ex -> safeReleaseLock(key, token));
    }

    private void persistOrRelease(IdempotencyKey key, IdempotencyStore.LockToken token,
                                  String payloadHash, CapturingResponseDecorator capturing) {
        int status = capturing.statusValue();
        if (!properties.shouldCache(status)) {
            log.debug("WebFlux idempotency skip cache (key={}, status={})", key.value(), status);
            safeReleaseLock(key, token);
            return;
        }
        try {
            byte[] body = capturing.capturedBody();
            IdempotencyRecord.Builder b = IdempotencyRecord.builder()
                .key(key.value())
                .payloadHash(payloadValidationEnabled() ? payloadHash : null)
                .statusCode(status)
                .body(body)
                .contentType(capturing.contentTypeValue())
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(properties.getTtl()));
            HttpHeaders headers = capturing.getHeaders();
            for (String name : headers.keySet()) {
                for (String value : headers.get(name)) {
                    b.addHeader(name, value);
                }
            }
            store.save(b.build(), token);
        } catch (IdempotencyStore.StoreException ex) {
            log.warn("WebFlux idempotency save failed (key={}): {}", key.value(), ex.getMessage());
        }
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
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(1024);

        CapturingResponseDecorator(ServerHttpResponse delegate) {
            super(delegate);
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
                buffer.write(bytes, 0, bytes.length);
                return getDelegate().bufferFactory().wrap(bytes);
            } finally {
                DataBufferUtils.release(dataBuffer);
            }
        }

        byte[] capturedBody() {
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
