package io.github.lu1tr0n.idempotency.core;

import java.util.List;

/**
 * Parses the per-response TTL override directive a handler may emit to store a
 * single idempotency record with a custom lifetime instead of the global
 * {@code spring.idempotency.ttl}.
 *
 * <p>The directive is carried in a response header (default name
 * {@code Idempotency-Persist-For}) whose value is a non-negative integer count
 * of <em>delta-seconds</em> — the same grammar HTTP uses for
 * {@code Cache-Control: max-age} (RFC 9111) and the delta-seconds form of
 * {@code Retry-After} (RFC 9110). It is a library-specific extension, not an
 * HTTP standard.
 *
 * <p>This helper is the single, stateless parse/clamp choke point shared by the
 * servlet and reactive filters, so both stacks resolve the directive
 * identically. It never throws and never logs — a malformed directive can never
 * fail the request (the response has already been sent by the time it is read).
 */
public final class ResponseTtlDirective {

    private ResponseTtlDirective() {
    }

    /**
     * Resolve the override to a clamped second count, or {@code -1} to signal
     * "ignore — fall back to the global TTL".
     *
     * <p>The value is honoured only when exactly one header value is present and
     * it is a positive, digits-only integer. Anything else — absent, blank,
     * non-numeric, signed, multi-valued, or {@code <= 0} — is ignored. A
     * {@code 0} is deliberately <strong>not</strong> treated as "do not cache";
     * that is the job of {@code non-cacheable-statuses}. A value above
     * {@code maxSeconds} is clamped <em>down</em> to {@code maxSeconds} so a
     * handler can never pin a record longer than the operator-configured
     * ceiling.
     *
     * @param values     the raw header values captured from the response (may be
     *                   {@code null} or empty)
     * @param maxSeconds the operator ceiling in seconds (already overflow-guarded)
     * @return the clamped lifetime in seconds, or {@code -1} to use the default
     */
    public static long resolveSeconds(List<String> values, long maxSeconds) {
        // A non-positive ceiling means the override is effectively inert — never
        // mint a 0-second (born-expired) record, which would silently defeat
        // idempotency. Fall back to the global TTL instead.
        if (maxSeconds <= 0) {
            return -1;
        }
        if (values == null || values.size() != 1) {
            return -1;
        }
        String raw = values.get(0) == null ? "" : values.get(0).trim();
        if (raw.isEmpty() || !isAllDigits(raw)) {
            return -1;
        }
        long seconds;
        try {
            seconds = Long.parseLong(raw);
        } catch (NumberFormatException overflow) {
            // A digit string too long for a long — treat as malformed, not as
            // "the largest possible TTL".
            return -1;
        }
        if (seconds <= 0) {
            return -1;
        }
        return Math.min(seconds, maxSeconds);
    }

    private static boolean isAllDigits(String s) {
        // ASCII digits only — matches the documented delta-seconds grammar and
        // excludes the non-ASCII digits Character.isDigit() would accept.
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
