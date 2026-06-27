package io.github.lu1tr0n.idempotency.exception;

import java.io.IOException;

/**
 * Raised when a keyed request body exceeds {@code spring.idempotency.max-body-size}
 * while it is being buffered for the idempotency payload fingerprint. Extends
 * {@link IOException} so it propagates out of the servlet body-snapshot path
 * naturally; the {@code IdempotencyFilter} catches this specific type (not a
 * bare {@code IOException}, which still signals a genuine transport error such
 * as a client disconnect) and maps it to {@code 413 Payload Too Large}.
 */
public class IdempotencyPayloadTooLargeException extends IOException {

    public IdempotencyPayloadTooLargeException(String message) {
        super(message);
    }
}
