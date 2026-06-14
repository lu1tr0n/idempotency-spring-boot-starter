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

### Redis *(v0.0.2)*

Coming next. Will activate automatically when `RedisConnectionFactory` is on the context and no `DataSource` is present, or when `spring.idempotency.backend=redis` is set explicitly.

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

## Roadmap

- **v0.0.1 (current)** — JDBC backend, servlet filter, payload validation, in-memory store for tests.
- **v0.0.2** — Redis backend, WebFlux filter, configurable 5xx-cache toggle.
- **v0.0.3** — Distributed tracing context propagation (OpenTelemetry / Brave).
- **v0.1.0** — First GA release; surface frozen; async / `Mono` / `CompletableFuture` support.

## License

[MIT](LICENSE)
