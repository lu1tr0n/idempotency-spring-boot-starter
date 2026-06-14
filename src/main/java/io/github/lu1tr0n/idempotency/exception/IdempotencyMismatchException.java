package io.github.lu1tr0n.idempotency.exception;

/**
 * Thrown by the filter when an incoming request reuses an idempotency key
 * that was previously stored, but the request payload hash does not match
 * the stored payload hash.
 *
 * <p>Translates to HTTP {@code 422 Unprocessable Entity} with an
 * {@code Idempotency-Mismatch: true} header so observability tooling can
 * count occurrences and alert. The body explains the mismatch — useful for
 * client developers who hit this in staging.
 *
 * <p>This is the Stripe-canonical behaviour: <em>"If you pass an
 * idempotency key with a request that we've already responded to, we'll
 * compare the parameters to make sure they match. If they don't, we'll
 * return an error to highlight the misuse."</em>
 */
public class IdempotencyMismatchException extends RuntimeException {

    private final String key;
    private final String storedPayloadHash;
    private final String incomingPayloadHash;

    public IdempotencyMismatchException(String key, String storedPayloadHash, String incomingPayloadHash) {
        super("Idempotency key '" + key + "' was previously used with a different request payload."
            + " Stored payload hash: " + storedPayloadHash
            + ", incoming payload hash: " + incomingPayloadHash);
        this.key = key;
        this.storedPayloadHash = storedPayloadHash;
        this.incomingPayloadHash = incomingPayloadHash;
    }

    public String key() { return key; }
    public String storedPayloadHash() { return storedPayloadHash; }
    public String incomingPayloadHash() { return incomingPayloadHash; }
}
