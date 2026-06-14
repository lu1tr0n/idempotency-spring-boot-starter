# idempotency-spring-boot-starter

[![Maven Central](https://img.shields.io/maven-central/v/io.github.lu1tr0n/idempotency-spring-boot-starter.svg)](https://central.sonatype.com/artifact/io.github.lu1tr0n/idempotency-spring-boot-starter)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Battle-grade idempotency for Spring Boot 3 — JDBC + Redis backends, **full HTTP response capture**, **Stripe-style payload validation**, Servlet support.

## Why another idempotency library?

`spring-idempotency-kit` (the only mature OSS option as of 2026) is Redis-only, AOP-only, stores response bodies as opaque strings, and doesn't validate payload mismatch. This starter targets the gaps:

|  | `spring-idempotency-kit` | this starter |
|---|---|---|
| Backend | Redis | **JDBC + Redis** (auto-detected) |
| Interception | AOP (`@Idempotent`) | **Servlet filter + `@Idempotent`** |
| Response replay | body + type name only | **status + headers + body + content-type** |
| Stripe-style payload mismatch | no | **yes (returns 422)** |
| Concurrent lock contention | 409 or WAIT | 409 with `Retry-After` |
| WebFlux | no | planned for v0.0.2 |

## Quick start

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.lu1tr0n:idempotency-spring-boot-starter:0.0.1")
}
```

```yaml
# application.yml
spring:
  idempotency:
    enabled: true             # default
    backend: jdbc             # jdbc | redis | in-memory | auto
    ttl: 24h                  # how long to keep stored records
    lock-timeout: 30s         # how long a lock lives before being stealable
    failure-strategy: fail-closed   # fail-closed | fail-open
    jdbc:
      auto-create-table: true # tests/dev only; use Flyway/Liquibase in prod
```

```bash
# Client passes the canonical Stripe-style header.
curl -X POST https://api.example.com/payments \
  -H "Idempotency-Key: pay_a8f3..." \
  -H "Content-Type: application/json" \
  -d '{"amount": 1000, "currency": "USD"}'
```

That's it. Every `POST`/`PUT`/`PATCH` that carries an `Idempotency-Key` header is now:

1. Executed exactly once per key.
2. Replayed verbatim on retries (`Idempotency-Replayed: true` response header on replays).
3. Rejected with `422 Unprocessable Entity` + `Idempotency-Mismatch: true` if the same key is reused with a different request body (Stripe-style misuse detection).
4. Rejected with `409 Conflict` + `Retry-After: 1` while another request with the same key is still in flight.

## Backends

### JDBC (default when a `DataSource` is present)

Schema is in [`schema-postgres.sql`](src/main/resources/io/github/lu1tr0n/idempotency/jdbc/schema-postgres.sql). Set it up with your preferred migration tool, or enable `spring.idempotency.jdbc.auto-create-table=true` for tests.

The JDBC backend is the right choice when:
- You already run PostgreSQL and don't want to add Redis just for idempotency.
- You want idempotency state to be transactionally consistent with the rest of your write.
- You can tolerate ~5 ms of latency for the lock + read round-trips.

### Redis (auto-activated when `RedisConnectionFactory` is present)

Activates automatically when `spring-boot-starter-data-redis` is on the classpath and no `DataSource` is present, or when `spring.idempotency.backend=redis` is set explicitly. Uses two keys per logical idempotency key (short-TTL lock + long-TTL record) with an atomic Lua script for the verify-token-and-set-and-delete path.

Optional knob: `spring.idempotency.redis.key-prefix` (default `idempotency:`) — override for multi-tenant Redis instances shared across apps.

The Redis backend is the right choice when:
- You already run Redis for caching/queueing and want zero additional infra.
- You need sub-millisecond lock acquisition.
- You can tolerate idempotency state being non-transactional with your business write (use JDBC instead for transactional consistency).

### In-memory (tests only)

```java
@TestConfiguration
class TestIdempotencyConfig {
    @Bean InMemoryIdempotencyStore inMemoryIdempotencyStore() {
        return new InMemoryIdempotencyStore();
    }
}
```

## Method-level overrides

The global filter applies to every mutating HTTP method by default. Use the annotation for per-endpoint overrides:

```java
@PostMapping("/payments")
@Idempotent(ttl = 7, timeUnit = TimeUnit.DAYS)   // override the global 24h TTL
public PaymentResponse pay(PaymentRequest req) { ... }

@GetMapping("/expensive-report")
@Idempotent(key = "#userId + ':' + #date")        // opt a GET in
public Report report(@AuthenticationPrincipal Long userId,
                     @RequestParam LocalDate date) { ... }

@PostMapping("/health")
@Idempotent(enabled = false)                      // opt out completely
public void health() { ... }
```

## Response headers

| Header | Meaning |
|---|---|
| `Idempotency-Replayed: true` | The response was served from cache; no business logic ran. |
| `Idempotency-Mismatch: true` | The supplied key was previously used with a different request body. |
| `Retry-After: <seconds>` | Concurrent request holds the lock; retry shortly. |

## Production toggles

`spring.idempotency.cache-5xx` (default `true`) — when `false`, 5xx responses are not cached. A transient downstream failure (502 / 503 / 504) releases the lock instead of poisoning the cache for the full TTL; the next retry can re-attempt the operation cleanly. Most production services should set this to `false`.

`spring.idempotency.failure-strategy` (default `fail-closed`) — `fail-closed` refuses requests when the storage layer is unreachable rather than risk a duplicate. `fail-open` lets requests through without idempotency guarantees; appropriate only for read-mostly or low-stakes endpoints.

`spring.idempotency.payload-validation` (default `enabled`) — Stripe-style detection of "same idempotency key, different request body" reuse. Disable only when clients legitimately retry with mutated bodies on the same key (rare).

## Roadmap

- **v0.0.1** — JDBC backend, servlet filter, payload validation, in-memory store for tests.
- **v0.0.2 (current)** — Redis backend, configurable 5xx-cache toggle, Testcontainers integration tests for Postgres + Redis, GitHub Packages mirror publish.
- **v0.0.3** — `@Idempotent` annotation AOP wiring, WebFlux filter, async / `Mono` / `CompletableFuture` support.
- **v0.0.4** — Distributed tracing propagation (OpenTelemetry / Brave), request-body size cap, Flyway migration script, multi-tenant key composition.
- **v0.0.5** — Micrometer metrics, Spring Boot Actuator health indicator.
- **v0.1.0** — First GA release; surface frozen; benchmarks vs `spring-idempotency-kit`; sample app; docs site.

## License

[MIT](LICENSE)
