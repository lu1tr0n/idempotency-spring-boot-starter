package io.github.lu1tr0n.idempotency.servlet;

import jakarta.servlet.http.HttpServletRequest;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;

import java.util.Optional;

/**
 * Reads the idempotency key from a single configurable HTTP header. Default
 * header name is {@code Idempotency-Key}, matching Stripe and the IETF draft
 * <em>The Idempotency-Key HTTP Header Field</em>
 * (https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/).
 *
 * <p>Empty / whitespace-only header values are treated as "no key supplied"
 * (returns empty), not as a malformed key. That matches the semantic most
 * clients expect: omitting the header opts out of idempotency tracking
 * entirely rather than triggering a 400.
 */
public final class HeaderIdempotencyKeyResolver implements IdempotencyKeyResolver {

    public static final String DEFAULT_HEADER = "Idempotency-Key";

    private final String headerName;

    public HeaderIdempotencyKeyResolver(String headerName) {
        if (headerName == null || headerName.isBlank()) {
            throw new IllegalArgumentException("headerName must not be blank");
        }
        this.headerName = headerName;
    }

    public HeaderIdempotencyKeyResolver() {
        this(DEFAULT_HEADER);
    }

    @Override
    public Optional<IdempotencyKey> resolve(HttpServletRequest request) {
        String raw = request.getHeader(headerName);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        // Surface malformed keys as 400 via the filter's IllegalArgumentException
        // handler; do not silently coerce to empty (silent coercion masks client bugs).
        return Optional.of(IdempotencyKey.of(raw));
    }

    public String headerName() {
        return headerName;
    }
}
