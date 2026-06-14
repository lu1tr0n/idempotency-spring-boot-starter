package io.github.lu1tr0n.idempotency.exception;

/**
 * Thrown when a request tries to acquire the idempotency lock for a key that
 * is already locked by an in-flight request. Translates to HTTP
 * {@code 409 Conflict} with a {@code Retry-After} header.
 *
 * <p>Two concurrent requests with the same key arriving within a few ms of
 * each other is the canonical case. The lock is released either when the
 * winner's response is stored (within the typical request lifetime) or when
 * the lock TTL expires (defaults to 30 seconds — covers crashed app servers).
 *
 * <p>Clients are expected to retry on 409 with their existing key after a
 * short backoff. After the winner's record is stored, subsequent retries
 * return the cached response.
 */
public class IdempotencyConflictException extends RuntimeException {

    private final String key;

    public IdempotencyConflictException(String key) {
        super("Idempotency key '" + key + "' is currently locked by another in-flight request. Retry after a short backoff.");
        this.key = key;
    }

    public String key() {
        return key;
    }
}
