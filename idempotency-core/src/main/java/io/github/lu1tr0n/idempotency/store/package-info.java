/**
 * Concrete {@link io.github.lu1tr0n.idempotency.core.IdempotencyStore}
 * implementations. Unlike the {@code core} package, these are
 * <strong>not</strong> part of the frozen SPI: {@link
 * io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore} ships here as a
 * pure-JDK reference implementation (and the store used in tests), and its API
 * — including the {@code visible for tests} helpers — may change between
 * releases. Implement {@code IdempotencyStore} from the {@code core} package,
 * not by subclassing this one, and use it as a worked example rather than a
 * stable dependency.
 */
package io.github.lu1tr0n.idempotency.store;
