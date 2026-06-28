# idempotency-spring-boot-starter

[![Maven Central](https://img.shields.io/maven-central/v/io.github.lu1tr0n/idempotency-spring-boot-starter.svg)](https://central.sonatype.com/artifact/io.github.lu1tr0n/idempotency-spring-boot-starter)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

> **Drop-in Idempotency-Key support for Spring Boot 3 APIs.** Every `POST` / `PUT` / `PATCH` retry returns the cached response — exact same body, status, headers — instead of charging twice, double-creating orders, or re-sending webhooks. JDBC and Redis backends, Stripe-style mismatch detection, zero controller changes.

## The problem

Mobile clients retry on flaky networks. Webhook senders retry on every 5xx. Payment gateways re-deliver callbacks. Without idempotency, every one of those retries can become a duplicate charge, a double-shipped order, or a re-sent email.

The standard fix is the [`Idempotency-Key` HTTP header](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/) (Stripe's convention, now an IETF draft): the client picks a unique key per logical operation; the server promises to execute the operation at most once per key and to replay the original response on every retry. Easy to specify, fiddly to implement: you need atomic locking, full HTTP response capture, payload mismatch detection, lock-expiry handling, fail-open vs fail-closed strategy, and a backend that survives restarts.

This starter does all of that with two lines of config.

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

#### Migrations (Flyway / Liquibase)

The starter bundles ready-made PostgreSQL migration artifacts. They live **off** the default auto-discovered paths, so they never merge into — or collide with — your own migration pipeline. Opt in explicitly:

**Flyway** — either reference the bundled location, or (recommended) copy the DDL into your own migration tree so you own the version number:

```properties
# option A: reference the bundled script
spring.flyway.locations=classpath:db/migration,classpath:db/idempotency/flyway/postgresql
```
```text
# option B (recommended): copy db/idempotency/flyway/postgresql/*.sql into
# src/main/resources/db/migration/V<your-next-version>__create_idempotency_records.sql
```

**Liquibase** — include the bundled changelog from your master:

```xml
<include file="db/idempotency/liquibase/db.changelog-idempotency.sql"/>
```

Notes:
- The artifacts hardcode the default table name `idempotency_records` (rename them if you override `spring.idempotency.jdbc.table-name`).
- Never edit a migration after it has been applied — both tools checksum it; ship changes as a new version/changeset.
- Only PostgreSQL is shipped. For MySQL, map `BYTEA → LONGBLOB`, `TIMESTAMP WITH TIME ZONE → DATETIME(6)` (and drop the `DEFAULT '{}'` on `response_headers` — MySQL rejects a literal default on `TEXT`; the store always writes the column explicitly). Pin the JDBC connection to UTC, since `DATETIME` is timezone-naive and the store works in UTC instants.
- The starter does not auto-evict — schedule `DELETE FROM idempotency_records WHERE expires_at < NOW()` (the `expires_at` index makes the sweep cheap).
- Records hold full HTTP response bodies + headers, which may contain sensitive data (PII, `Set-Cookie` / `Authorization`). Use database encryption-at-rest and keep the TTL tight if that applies to you.

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

`spring.idempotency.max-body-size` (default `1MB`) — maximum request body buffered to compute the payload fingerprint. A keyed request larger than this is rejected with `413` before any store work. Only keyed requests are buffered, so unkeyed uploads are unaffected. Set to `-1` to disable the cap.

`spring.idempotency.max-response-size` (default `1MB`) — maximum response body buffered to snapshot into the store. A larger response is streamed to the client in full but is **not cached** (the lock is released, logged at `WARN`), so a retry re-executes the handler. Raise it for endpoints that legitimately return large responses; set to `-1` to disable. Response *headers* are not bounded by this.

`spring.idempotency.observations.enabled` (default `true`) — emit a Micrometer observation per idempotency outcome (`idempotency.outcome` = replayed / executed_stored / lock_held / payload_mismatch / payload_too_large / executed_not_stored, plus `idempotency.status`). Produces a span when a tracer (OpenTelemetry or Brave) is wired and an `idempotency` counter when a meter registry is — a free no-op otherwise. The idempotency key and principal are never emitted (PII / cardinality). Servlet only for now.

`spring.idempotency.non-cacheable-statuses` (default empty) — handler response statuses that are *not* saved as the idempotency record; the lock is released so the same key is reusable on a corrected retry. A sensible opt-in set is `400,401,403,429` (the operation never committed). Leave committed outcomes (`402`/`404`/`409`/`422`) out so they keep replaying. Note: unlike Stripe — which caches executed errors and expects a fresh key — a released key carries no stored payload hash, so a corrected retry may use a different body.

## Actuator health

When Spring Boot Actuator is on the classpath, the starter contributes an `idempotency` health indicator that probes the configured store — for the JDBC backend a `SELECT 1 FROM <table> WHERE 1 = 0`, which also confirms the **idempotency table exists** (catching the "migration never ran" case a plain `db` indicator misses); for Redis a `PING`. It exposes only low-cardinality details (`backend`, `failureStrategy`) — never keys, principals, table names, or row counts.

Severity tracks `failure-strategy`, so health never manufactures an outage:

| store probe | `failure-strategy` | status |
|---|---|---|
| ok | any | `UP` |
| fails | `fail-closed` | `DOWN` (the app truly refuses keyed mutations) |
| fails | `fail-open` | `UP` + `degraded: true` (the app still serves; failure logged at `WARN`) |
| no probe (custom store) | any | `UNKNOWN` + `probe: unsupported` |

The indicator is **not** in the readiness group and never in liveness — a shared-store outage must not depool the whole fleet or kill pods. Opt in deliberately if you want store outage to gate traffic:

```yaml
management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState, idempotency
```

A consumer store can participate by implementing `io.github.lu1tr0n.idempotency.core.IdempotencyStoreHealth` (a single `verify()` method, no Actuator dependency).

`spring.idempotency.health.cache-ttl` (default `1s`) — how long a probe result is cached before the store is re-probed, so several probers (Kubernetes, load balancer, Prometheus) hitting `/actuator/health` cost at most one backend probe per interval and flapping is damped. Set to `0` to probe on every call.

`management.health.idempotency.enabled` (default `true`) — the standard Actuator switch to disable the indicator entirely.

**Operational notes.** Keep `/actuator/health` off the public internet and prefer `management.endpoint.health.show-details=when-authorized`; on a store outage the `error` detail can include the backend host or relation name (same exposure as Spring's own `db` / `redis` indicators, and only rendered when details are shown). The default `cache-ttl=1s` bounds backend probes to roughly one per second regardless of how many probers poll; setting `cache-ttl=0` removes that bound, so leave it positive on a busy or exposed endpoint. The Redis probe fails as fast as the Redis client's command timeout (the JDBC probe has its own 2s query timeout). With `failure-strategy=fail-open`, the indicator stays `UP` (with `degraded: true`) while the idempotency guarantee is silently off — alert on the WARN log or the `idempotency` outcome metric, not on the health rollup.

## Lock extension (heartbeat)

By default a lock lives for `lock-timeout` (30s). A handler that runs *longer* than that lets its lock expire, and a concurrent retry can then steal it and run the operation a second time. The two clocks are independent — the short in-flight lock (`lock-timeout`) and the long completed-record TTL (`ttl`) — so set `lock-timeout` above your slowest protected handler and you avoid this entirely.

When a handler's duration is unpredictable, opt into the heartbeat:

```yaml
spring:
  idempotency:
    lock-timeout: 30s
    lock-extension:
      enabled: true        # default false
      interval: 10s        # optional; defaults to lock-timeout / 3
```

While the handler runs, a background daemon renews the lock every `interval` (sliding it forward by a full `lock-timeout`), so it only lapses if the owning process actually dies or loses the store. Renewal is token-checked and a no-op once the request completes. A single daemon thread services all in-flight renewals, so under very high concurrency of long-running handlers the heartbeat is best-effort — a renewal that misses its window degrades that key to plain `lock-timeout` expiry. In the unlikely event a renewal is leaked (the request's `finally` never runs), it self-terminates after `ttl`, so a leak blocks retries of that one key for at most the record TTL — never forever. Supported on the JDBC, Redis and in-memory stores; against a custom store that does not implement the extension contract the heartbeat is disabled with a startup warning rather than silently believed active.

> **It narrows the duplicate-execution window — it does not close it.** The heartbeat only guarantees that a lock held by a *live, healthy, store-reachable* owner will not expire mid-handler. It is **not** an at-most-once guarantee. If the owning process suffers a stop-the-world pause (GC, container freeze) or loses connectivity to the store for longer than `lock-timeout`, the renewal cannot land, the lease lapses by design, a concurrent retry may steal the lock, and the handler may run twice. The library issues no fencing token to the downstream resource, so it cannot reject a write from a stale owner that resumes after its lease expired. For hard at-most-once side effects, make the side effect itself idempotent at the resource (unique constraint, conditional write) — which remains necessary with or without the heartbeat.

## L1 cache (Caffeine)

A hot key retried in a tight loop (mobile reconnects, client retry storms) hits the distributed store on every replay. An optional in-process **L1 cache** (Caffeine) sits in front of the JDBC / Redis **L2** store and serves those replays from local heap. Off by default; add Caffeine to the classpath and opt in:

```yaml
spring:
  idempotency:
    cache:
      enabled: true          # opt-in; requires com.github.ben-manes.caffeine:caffeine
      ttl: 2m                # L1 entry lifetime (working-set knob, not a correctness window)
      maximum-weight: 64MB   # hard heap ceiling, enforced by a byte weigher (not entry count)
      max-entry-size: 256KB  # records larger than this are served from L2, never cached in L1
      record-stats: true     # publish hit/miss/eviction under idempotency.l1 when a MeterRegistry exists
```

```kotlin
// build.gradle.kts — the cache requires Caffeine on the consumer classpath
implementation("com.github.ben-manes.caffeine:caffeine")
```

**It only caches completed replays — never the lock.** The L1 holds immutable completed records (`findRecord` positives). The atomic lock acquisition that guarantees at-most-once is *never* served from L1; it always goes to the distributed store, so the cache cannot cause a second execution. Absent keys are never cached, and every L1 hit is re-validated against the record's own `expiresAt`, so a key reused with a different payload after its first record expired never replays a stale response. The result is byte-identical to an L2 replay — payload-mismatch (422) detection is unaffected.

**Sizing is by bytes, not entry count.** A record holds the full response body (up to `max-response-size`, default 1MB), so a count-based cap would be a memory trap (10k entries × 1MB = 10GB). The cache is bounded by `maximum-weight` over body + header bytes, plus a fixed per-entry floor so even tiny/empty responses cannot flood the heap; `max-entry-size` keeps large bodies out of the working set entirely.

`cache.ttl` is a memory/working-set knob — keep it short (default 2m). It is **not** a correctness window: the record's own `ttl` (default 24h) still governs replay validity via the read-time expiry check. Do not set `cache.ttl` to the record `ttl`, or every completed response would be pinned in heap for a day. Wrapping the in-memory backend is a no-op (logged at `INFO`) — an L1 in front of an in-process map adds nothing.

> **Data residency.** An enabled L1 keeps full response bodies and headers (which may include `Set-Cookie`, bearer tokens or PII) resident in process heap for `cache.ttl` — strictly shorter and smaller than the L2 store, which already persists the same surface for the record `ttl`. For endpoints handling regulated data, use a shorter `cache.ttl` or leave the L1 off.

## Roadmap

- **v0.0.1** — JDBC backend, servlet filter, payload validation, in-memory store for tests.
- **v0.0.2** — Redis backend, configurable 5xx-cache toggle, Testcontainers integration tests for Postgres + Redis, GitHub Packages mirror publish.
- **v0.0.2.1** — critical fixes: filter wiring on JDBC/Redis backends, 4xx response body replay, per-platform JDBC schema (Postgres/H2), TTL-expired record steal, in-memory auto-config, `default-ttl` alias.
- **v0.0.3** — `@Idempotent` annotation AOP wiring, WebFlux filter, async / `Mono` / `CompletableFuture` support.
- **v0.0.4 (current)** — Security & standards hardening:
  - RFC 8941 sf-string parsing (strip surrounding quotes — forward-compat with IETF draft -08+)
  - Composite key with authenticated principal (IETF draft §5 data-leak mitigation)
  - `@RequireIdempotencyKey` — enforce the key on selected endpoints (IETF §2.7 missing-key → 400)
  - Request- and response-body size caps (DoS protection)
  - Configurable non-cacheable response statuses (release the lock so a corrected retry reuses the key)
  - Distributed tracing / metrics via Micrometer Observation (per-outcome span + counter; OpenTelemetry / Brave; servlet)
  - Flyway / Liquibase migration scripts (PostgreSQL)
- **v0.0.5 (current)** — Operability:
  - Spring Boot Actuator health indicator (store reachability + table existence; severity tracks `failure-strategy`) ✓
  - Lock-extension heartbeat (renew a long-running handler's lock so a concurrent retry can't steal it) ✓
  - L1 + L2 cache layering (optional Caffeine cache in front of Redis/JDBC for hot-key replays) ✓
  - Per-response TTL override header (`X-Idempotency-Persist-For-Seconds`)
- **v0.0.6** — Extensibility: public `IdempotencyStore` SPI + multi-module layout (`idempotency-core` + `idempotency-store-{jdbc,redis,memory,…}`) so third parties can ship DynamoDB / MongoDB / Cosmos DB / etc. backends.
- **v0.1.0** — First GA release; surface frozen; benchmarks; sample app; docs site.

## License

[MIT](LICENSE)
