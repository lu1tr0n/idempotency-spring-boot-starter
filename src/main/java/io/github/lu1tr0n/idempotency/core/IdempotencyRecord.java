package io.github.lu1tr0n.idempotency.core;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full snapshot of a completed idempotent operation, as stored by the
 * {@link IdempotencyStore} and replayed verbatim on subsequent retries.
 *
 * <p>Unlike {@code spring-idempotency-kit} which stores only
 * {@code (body, typeName)} as strings, this record preserves the entire HTTP
 * response surface — status code, headers, body bytes, content type — so that
 * a replay is byte-for-byte equivalent to the original response. That matters
 * when the response includes cache-control headers, ETags, location headers
 * (POST → 201 with {@code Location} pointing at the created resource), or
 * {@code Set-Cookie}: replaying only the body would silently drop those.
 *
 * <p>The {@code payloadHash} field enables Stripe-style mismatch detection: a
 * replay with the same key but a different request body returns 422 instead
 * of the cached response. Without payload validation, a client bug that
 * reuses a key for a logically different operation silently returns the wrong
 * answer.
 *
 * <p>Construct via {@link Builder} — the field count is high enough to make
 * positional construction error-prone.
 */
public final class IdempotencyRecord {

    private final String key;
    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final String contentType;
    private final String payloadHash;
    private final Instant createdAt;
    private final Instant expiresAt;

    private IdempotencyRecord(Builder b) {
        this.key = b.key;
        this.statusCode = b.statusCode;
        this.headers = Map.copyOf(b.headers);
        this.body = b.body == null ? new byte[0] : b.body.clone();
        this.contentType = b.contentType;
        this.payloadHash = b.payloadHash;
        this.createdAt = b.createdAt;
        this.expiresAt = b.expiresAt;
    }

    public String key() { return key; }
    public int statusCode() { return statusCode; }
    public Map<String, List<String>> headers() { return headers; }
    /** Returns a defensive copy — never mutate the returned array. */
    public byte[] body() { return body.clone(); }
    public String contentType() { return contentType; }
    public String payloadHash() { return payloadHash; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable builder. Not thread-safe — build the record on one thread, then
     * publish it as immutable to the store.
     */
    public static final class Builder {
        private String key;
        private int statusCode = 200;
        private Map<String, List<String>> headers = new LinkedHashMap<>();
        private byte[] body;
        private String contentType;
        private String payloadHash;
        private Instant createdAt = Instant.now();
        private Instant expiresAt;

        public Builder key(String key) { this.key = key; return this; }
        public Builder statusCode(int code) { this.statusCode = code; return this; }
        public Builder headers(Map<String, List<String>> headers) {
            this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
            return this;
        }
        public Builder addHeader(String name, String value) {
            this.headers.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(value);
            return this;
        }
        public Builder body(byte[] body) { this.body = body; return this; }
        public Builder contentType(String contentType) { this.contentType = contentType; return this; }
        public Builder payloadHash(String hash) { this.payloadHash = hash; return this; }
        public Builder createdAt(Instant when) { this.createdAt = when; return this; }
        public Builder expiresAt(Instant when) { this.expiresAt = when; return this; }

        public IdempotencyRecord build() {
            if (key == null || key.isEmpty()) {
                throw new IllegalStateException("IdempotencyRecord requires a non-empty key");
            }
            if (expiresAt == null) {
                throw new IllegalStateException("IdempotencyRecord requires an expiresAt — set it from IdempotencyProperties.getTtl()");
            }
            if (headers == null) {
                headers = Collections.emptyMap();
            }
            return new IdempotencyRecord(this);
        }
    }
}
