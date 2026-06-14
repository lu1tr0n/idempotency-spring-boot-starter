package io.github.lu1tr0n.idempotency.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes a stable hash of the request payload, used to detect Stripe-style
 * "same idempotency key, different body" misuse.
 *
 * <p>Algorithm: SHA-256 truncated to the hex form (64 chars). SHA-256 is fast
 * enough on modern hardware (~3 GB/s with AES-NI) that hashing each request
 * body adds negligible latency, and collision probability is irrelevant for
 * any realistic workload.
 *
 * <p>What we hash:
 *
 * <ul>
 *   <li>HTTP method (POST, PUT, PATCH)</li>
 *   <li>URI path (not query string — typically clients pass dedupe data in body)</li>
 *   <li>Body bytes, verbatim</li>
 * </ul>
 *
 * <p>What we deliberately don't hash:
 *
 * <ul>
 *   <li>Headers (auth tokens rotate; tracing IDs change per request).</li>
 *   <li>Query string (could include cache busters or analytics params).</li>
 *   <li>Authenticated principal (different users with the same key on different
 *       tenants must produce different keys at the resolver level, not here).</li>
 * </ul>
 */
public final class PayloadHasher {

    private static final String ALGORITHM = "SHA-256";
    private static final HexFormat HEX = HexFormat.of();

    private PayloadHasher() {
        // Static-only.
    }

    public static String hash(String httpMethod, String requestUri, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            digest.update(httpMethod.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0x00); // domain separator between fields
            digest.update(requestUri.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0x00);
            if (body != null && body.length > 0) {
                digest.update(body);
            }
            return HEX.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE since 1.4. Reaching this branch
            // means the runtime is broken in ways that make idempotency the
            // least of your problems.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
