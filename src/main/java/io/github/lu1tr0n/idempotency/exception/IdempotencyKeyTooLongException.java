package io.github.lu1tr0n.idempotency.exception;

/**
 * Raised when folding the authenticated principal into the key would exceed the
 * {@code IdempotencyKey} length budget. Extends {@link IllegalArgumentException}
 * so the servlet filter's existing malformed-key handler maps it to a 400; the
 * reactive filter catches this specific type (rather than a bare
 * {@code IllegalArgumentException}) so it never swallows an unrelated
 * downstream error.
 */
public class IdempotencyKeyTooLongException extends IllegalArgumentException {

    public IdempotencyKeyTooLongException(String message) {
        super(message);
    }
}
