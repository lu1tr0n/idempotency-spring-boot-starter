package io.github.lu1tr0n.idempotency.reactive;

import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyProperties;
import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.core.PayloadHasher;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
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
import java.time.Duration;
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

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final Set<String> trackedMethods;

    public IdempotencyWebFilter(IdempotencyStore store, IdempotencyProperties properties) {
        this.store = store;
        this.properties = properties;
        this.trackedMethods = Set.copyOf(properties.getMethods().stream()
            .map(m -> m.toUpperCase(Locale.ROOT))
            .toList());
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

        return materialiseBody(request)
            .flatMap(bodyBytes -> applyIdempotency(exchange, chain, key, bodyBytes));
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
        if (!properties.isCache5xx() && status >= 500 && status < 600) {
            log.debug("WebFlux idempotency skip cache for 5xx (key={}, status={})", key.value(), status);
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
        return DataBufferUtils.join(request.getBody())
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
        // Run early — before security filters that may short-circuit so
        // replays don't waste downstream work.
        return Ordered.HIGHEST_PRECEDENCE + 100;
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

    @SuppressWarnings("unused") // placeholder for backward-compat with callers
    private Duration unused() { return Duration.ZERO; }
}
