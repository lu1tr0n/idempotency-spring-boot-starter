package io.github.lu1tr0n.idempotency.exception;

/**
 * Raised when {@code spring.idempotency.principal-binding=required} is in force
 * and a tracked request supplies an idempotency key but no authenticated
 * principal can be resolved to scope it to. The surrounding filter / aspect
 * translates this into a {@code 422 Unprocessable Entity} with the
 * {@code IDEMPOTENCY_PRINCIPAL_REQUIRED} error code — the key is well-formed,
 * but the configured policy cannot be satisfied.
 */
public class IdempotencyPrincipalRequiredException extends RuntimeException {

    public IdempotencyPrincipalRequiredException(String message) {
        super(message);
    }
}
