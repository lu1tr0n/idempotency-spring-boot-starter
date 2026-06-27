package io.github.lu1tr0n.idempotency.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint (or every endpoint on a controller) as <em>requiring</em>
 * the {@code Idempotency-Key} request header. A request that reaches such a
 * handler without the header is rejected with {@code 400 Bad Request} before
 * the handler runs — implementing the "missing key" case of the IETF draft
 * <em>The Idempotency-Key HTTP Header Field</em> (§2.7).
 *
 * <p>By default idempotency is opt-in: the global {@code IdempotencyFilter}
 * tracks {@code POST}/{@code PUT}/{@code PATCH} requests that carry the key and
 * silently passes through the ones that don't. This annotation flips that for
 * the endpoints where a missing key is a client error rather than a choice —
 * payments, transfers, order placement.
 *
 * <pre>{@code
 * @RestController
 * @RequireIdempotencyKey                 // applies to every handler below
 * class PaymentController {
 *
 *     @PostMapping("/payments")
 *     Receipt pay(@RequestBody PaymentRequest req) { ... }
 *
 *     @PostMapping("/refunds")           // also requires the key
 *     Receipt refund(@RequestBody RefundRequest req) { ... }
 * }
 *
 * @PostMapping("/orders")
 * @RequireIdempotencyKey                 // or just this one endpoint
 * Order place(@RequestBody OrderRequest req) { ... }
 * }</pre>
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>"Required" means the header is <strong>present and non-blank</strong>.
 *       Grammar validation stays with the filter: a present-but-malformed key
 *       already returns {@code 400 INVALID_IDEMPOTENCY_KEY} before the handler
 *       is reached, so this annotation never has to re-check it.</li>
 *   <li>The rejection is a pure precondition failure — the handler never
 *       executes, so there are no side effects, and the {@code 400} is never
 *       stored as an idempotency record. A later retry that supplies a valid
 *       key is processed fresh.</li>
 *   <li>Presence is checked against the configured
 *       {@code spring.idempotency.header-name} header directly — not through a
 *       custom {@code IdempotencyKeyResolver}. The contract is specifically
 *       "this endpoint requires the idempotency-key header".</li>
 * </ul>
 *
 * <h2>Scope</h2>
 * <p>Enforcement is implemented for the servlet (Spring MVC) stack via a
 * {@code HandlerInterceptor}. WebFlux is not covered in this release: a
 * reactive {@code WebFilter} cannot see the resolved handler annotation before
 * the handler runs. Reactive applications should enforce the requirement in the
 * handler itself or with a custom {@code WebFilter} keyed on the path.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireIdempotencyKey {
}
