package io.github.lu1tr0n.idempotency.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Marks a controller method as requiring idempotent execution.
 *
 * <p>The global {@code IdempotencyFilter} already applies idempotency to every
 * {@code POST} / {@code PUT} / {@code PATCH} request that carries the
 * configured key header. This annotation is the escape hatch for cases where
 * the global default is not enough:
 *
 * <ul>
 *   <li>Opting a GET endpoint in (rare, but valid for expensive aggregations).</li>
 *   <li>Opting out via {@link #enabled()}<code>=false</code> for a single endpoint
 *       you do not want the global filter to track.</li>
 *   <li>Overriding the TTL ({@link #ttl()}) for a single endpoint without changing
 *       global properties.</li>
 *   <li>Deriving the key from method arguments via a SpEL expression
 *       ({@link #key()}) when the request body is parsed before the filter sees it.</li>
 * </ul>
 *
 * <p>When neither the global filter nor this annotation can resolve a key for a
 * request, the request is processed normally and no record is stored — idempotency
 * is always opt-in at the data level.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * SpEL expression evaluated against the method invocation context to derive
     * the idempotency key. When set, takes precedence over the request header.
     *
     * <p>Examples:
     * <pre>{@code
     * @Idempotent(key = "#request.id")
     * public PaymentResponse pay(PaymentRequest request) { ... }
     *
     * @Idempotent(key = "#orderId + ':' + #userId")
     * public void cancelOrder(@PathVariable String orderId,
     *                         @AuthenticationPrincipal Long userId) { ... }
     * }</pre>
     */
    String key() default "";

    /**
     * Per-method TTL override. {@code -1} means "use the global default from
     * {@code spring.idempotency.ttl}".
     */
    long ttl() default -1;

    /**
     * Time unit for {@link #ttl()}. Defaults to hours to match the Stripe-style
     * 24-hour idempotency window most APIs converge on.
     */
    TimeUnit timeUnit() default TimeUnit.HOURS;

    /**
     * When {@code false}, this method is excluded from idempotency tracking even
     * if the global filter would otherwise apply. Use when the global filter is
     * enabled but a specific endpoint must not be tracked (e.g. health checks
     * mapped to {@code POST} for legacy reasons).
     */
    boolean enabled() default true;
}
