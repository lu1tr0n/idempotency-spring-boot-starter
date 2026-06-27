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
        // Bound work before the unescape allocation, independent of the servlet
        // header-size cap (the AOP/resolver path can source a value from
        // non-header inputs). The longest a valid key can be once unquoted is
        // MAX_LENGTH, and the longest sf-string that can yield it is every
        // character escaped plus the two quotes: 2*MAX_LENGTH + 2. Anything
        // longer cannot be a valid key, so reject it cheaply.
        if (raw.length() > 2 * MAX_LENGTH + 2) {
            throw new IllegalArgumentException(
                "Idempotency key must be 1.." + MAX_LENGTH + " characters; got length " + raw.length());
        }
        // The IETF draft (-08+) carries the key as an RFC 8941 Structured Field
        // sf-string — surrounded by double quotes: `Idempotency-Key: "k-1"`.
        // Stripe and earlier drafts send the bare token `k-1`. Accept both by
        // unquoting a well-formed sf-string before validation; a bare value is
        // passed through untouched, so this stays backwards-compatible.
        String value = unquoteStructuredField(raw);
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                "Idempotency key must be 1.." + MAX_LENGTH + " characters; got length " + value.length());
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isAllowedChar(c)) {
                throw new IllegalArgumentException(
                    "Idempotency key contains illegal character at position " + i
                        + "; only [A-Za-z0-9_:-] are allowed");
            }
        }
        return new IdempotencyKey(value);
    }

    /**
     * Strips one RFC 8941 sf-string wrapper if present, unescaping the only two
     * legal in-string escapes ({@code \"} and {@code \\}). A value that is not a
     * well-formed quoted string (no surrounding quotes, or a lone quote) is
     * returned unchanged so bare Stripe-style keys keep working; any leftover
     * quote then fails the character check below and yields a 400.
     */
    private static String unquoteStructuredField(String raw) {
        if (raw.length() < 2 || raw.charAt(0) != '"' || raw.charAt(raw.length() - 1) != '"') {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length() - 2);
        for (int i = 1; i < raw.length() - 1; i++) {
            char c = raw.charAt(i);
            if (c == '\\') {
                // A trailing backslash before the closing quote, or one that
                // escapes anything other than " or \, is not a valid sf-string;
                // leave it literal so validation rejects it rather than guessing.
                if (i + 1 >= raw.length() - 1) {
                    return raw;
                }
                char next = raw.charAt(i + 1);
                if (next != '"' && next != '\\') {
                    return raw;
                }
                sb.append(next);
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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
