/**
 * Public storage SPI for idempotency. This package is the supported extension
 * surface: implement {@link io.github.lu1tr0n.idempotency.core.IdempotencyStore}
 * to add a backend (DynamoDB, MongoDB, Cassandra, ...) and register it as a
 * bean. A backend author depends on {@code io.github.lu1tr0n:idempotency-core}
 * alone and never pulls the web / AOP / servlet machinery of the starter.
 *
 * <h2>Dependency-free by design</h2>
 *
 * <p>This module has <strong>no</strong> Spring, Jakarta, or third-party
 * compile dependencies — it is pure JDK. That is enforced structurally: the
 * {@code idempotency-core} Gradle module declares no such dependencies, so any
 * accidental framework import here fails to compile. Keep it that way; a
 * backend author must be able to implement the SPI without inheriting a web
 * stack.
 *
 * <h2>Stability</h2>
 *
 * <p>The types in this package are the frozen SPI. Within a {@code 0.0.x} line
 * they will not change in a source- or binary-incompatible way. Breaking
 * changes to the SPI happen only on a {@code 0.x} minor bump and go through a
 * deprecation cycle. Everything outside this package (the servlet / reactive
 * filters, the AOP aspect, the auto-configuration, and the concrete
 * {@code jdbc} / {@code redis} / {@code cache} stores) is internal and may
 * change at any time.
 */
package io.github.lu1tr0n.idempotency.core;
