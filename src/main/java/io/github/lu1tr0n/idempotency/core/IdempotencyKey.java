package io.github.lu1tr0n.idempotency.core;

import java.util.Objects;

/**
 * Validated wrapper around the raw key string supplied by the client.
 *
 * <p>Validation rules — opinionated, can be relaxed via configuration in a
 * future release if real consumers push back:
 *
 * <ul>
 *   <li>Length 1..255 — fits in a varchar(255) JDBC column comfortably and
 *       leaves room for tenant-prefix composition.</li>
 *   <li>Characters: alphanumeric, hyphen, underscore, colon. Excludes
 *       whitespace and SQL/Redis special chars on purpose; a permissive key
 *       grammar is the #1 vector for cache-collision bugs and key injection.</li>
 *   <li>Stripping is the caller's job — we reject leading/trailing whitespace
 *       loudly rather than silently mutate the key (silent mutation produces
 *       distinct keys for "abc" and " abc " across services that differ on
 *       whether they trim).</li>
 * </ul>
 *
 * <p>Use {@link #of(String)} to construct. The constructor is package-private
 * to force going through validation.
 */
public final class IdempotencyKey {

    public static final int MAX_LENGTH = 255;

    private final String value;

    private IdempotencyKey(String value) {
        this.value = value;
    }

    /**
     * Parses and validates a raw key string. Returns a typed key on success or
     * throws {@link IllegalArgumentException} on a validation failure — the
     * surrounding filter / aspect translates that into a {@code 400 Bad Request}
     * with a structured error body.
     */
    public static IdempotencyKey of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Idempotency key must not be null");
        }
        if (raw.isEmpty() || raw.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                "Idempotency key must be 1.." + MAX_LENGTH + " characters; got length " + raw.length());
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!isAllowedChar(c)) {
                throw new IllegalArgumentException(
                    "Idempotency key contains illegal character at position " + i
                        + "; only [A-Za-z0-9_:-] are allowed");
            }
        }
        return new IdempotencyKey(raw);
    }

    private static boolean isAllowedChar(char c) {
        return (c >= 'A' && c <= 'Z')
            || (c >= 'a' && c <= 'z')
            || (c >= '0' && c <= '9')
            || c == '-' || c == '_' || c == ':';
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof IdempotencyKey that)) return false;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "IdempotencyKey[" + value + "]";
    }
}
