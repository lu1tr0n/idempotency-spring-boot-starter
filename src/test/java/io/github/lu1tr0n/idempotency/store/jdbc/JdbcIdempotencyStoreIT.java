package io.github.lu1tr0n.idempotency.store.jdbc;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests against a real PostgreSQL 16 container. Covers the
 * surface that H2 cannot validate: JSONB headers, BYTEA bodies, atomic
 * INSERT-or-steal-expired-lock semantics, and TIMESTAMP WITH TIME ZONE
 * comparisons.
 *
 * <p>Skipped in environments without Docker. The full CI runs Docker, so
 * the suite gates every PR.
 */
@Testcontainers
class JdbcIdempotencyStoreIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("idempotency_test")
            .withUsername("test")
            .withPassword("test");

    JdbcTemplate jdbc;
    IdempotencyStore store;

    @BeforeEach
    void setUp() throws IOException {
        DataSource ds = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        // Apply the bundled Postgres schema, then truncate so each test starts clean.
        try (InputStream in = JdbcIdempotencyStoreIT.class.getResourceAsStream(
            "/io/github/lu1tr0n/idempotency/jdbc/schema-postgres.sql")) {
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Strip line-comments BEFORE splitting on `;`. The schema file
            // starts with a long comment block; the previous version of
            // this loader split on `;` first and dropped the entire first
            // chunk (comment block + CREATE TABLE) because it started
            // with `--`. Strip per-line so the CREATE TABLE survives.
            StringBuilder cleaned = new StringBuilder(raw.length());
            for (String line : raw.split("\n")) {
                int commentStart = line.indexOf("--");
                cleaned.append(commentStart >= 0 ? line.substring(0, commentStart) : line).append('\n');
            }
            for (String statement : cleaned.toString().split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    jdbc.execute(trimmed);
                }
            }
        }
        jdbc.update("TRUNCATE TABLE idempotency_records");

        store = new JdbcIdempotencyStore(jdbc, "idempotency_records");
    }

    @Test
    void saveAndReplay_roundTripsHeadersAndBody() {
        IdempotencyKey key = IdempotencyKey.of("rt-001");
        Optional<IdempotencyStore.LockToken> token = store.acquireLock(key, Duration.ofSeconds(30));
        assertThat(token).isPresent();

        IdempotencyRecord record = IdempotencyRecord.builder()
            .key(key.value())
            .statusCode(201)
            .body("{\"id\":\"order-1\"}".getBytes(StandardCharsets.UTF_8))
            .contentType("application/json")
            .payloadHash("abc123")
            .addHeader("Location", "/orders/order-1")
            .addHeader("X-Trace", "trace-001")
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(Duration.ofHours(1)))
            .build();
        store.save(record, token.get());

        Optional<IdempotencyRecord> replayed = store.findRecord(key);
        assertThat(replayed).isPresent();
        IdempotencyRecord r = replayed.get();
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(new String(r.body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"order-1\"}");
        assertThat(r.contentType()).isEqualTo("application/json");
        assertThat(r.payloadHash()).isEqualTo("abc123");
        assertThat(r.headers()).containsKeys("Location", "X-Trace");
        assertThat(r.headers().get("Location")).containsExactly("/orders/order-1");
    }

    @Test
    void findRecord_returnsEmptyWhileLockHeldInFlight() {
        // Contract: findRecord must return empty when only a lock (no response
        // yet) exists. Regression for the JDBC store leaking in-flight lock
        // rows: the table doubles as the lock table, and lock rows carry an
        // empty (non-null) body with a future expires_at — so the completion
        // guard must be `lock_token IS NULL`, not `response_body IS NOT NULL`.
        IdempotencyKey key = IdempotencyKey.of("in-flight-001");
        Optional<IdempotencyStore.LockToken> lock = store.acquireLock(key, Duration.ofSeconds(30));
        assertThat(lock).isPresent();

        // A concurrent second caller looks up the record while the lock is held.
        assertThat(store.findRecord(key)).isEmpty();
    }

    @Test
    void acquireLock_secondCallerSeesEmptyOptional() {
        IdempotencyKey key = IdempotencyKey.of("lock-contention-001");
        Optional<IdempotencyStore.LockToken> first = store.acquireLock(key, Duration.ofSeconds(30));
        Optional<IdempotencyStore.LockToken> second = store.acquireLock(key, Duration.ofSeconds(30));

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
    }

    @Test
    void acquireLock_canStealExpiredLock() {
        IdempotencyKey key = IdempotencyKey.of("expired-lock-001");
        // Seed a "crashed in-flight lock" directly: an empty-body row whose
        // locked_until is already in the past. Seeding via SQL (instead of
        // acquireLock + Thread.sleep) keeps the test deterministic — it
        // asserts the steal precondition exactly, with no dependency on
        // wall-clock timing under load. The production steal path
        // (locked_until < now) is exercised identically; only how the row's
        // locked_until reached the past differs.
        Instant past = Instant.now().minus(Duration.ofMinutes(5));
        jdbc.update(
            "INSERT INTO idempotency_records (idempotency_key, http_status, response_headers,"
                + " response_body, created_at, expires_at, lock_token, locked_until)"
                + " VALUES (?, 0, '{}', ?, ?, ?, ?, ?)",
            key.value(), new byte[0], Timestamp.from(past), Timestamp.from(past),
            "stale-token-from-crashed-server", Timestamp.from(past));

        Optional<IdempotencyStore.LockToken> stolen = store.acquireLock(key, Duration.ofSeconds(30));

        assertThat(stolen).isPresent();
        // The stealer owns a fresh token, not the crashed server's stale one.
        assertThat(stolen.get().toString()).doesNotContain("stale-token-from-crashed-server");
        // And no stale response is replayable after the steal.
        assertThat(store.findRecord(key)).isEmpty();
    }

    @Test
    void save_withStaleTokenIsRejected() {
        IdempotencyKey key = IdempotencyKey.of("stale-token-001");
        IdempotencyStore.LockToken realToken = store.acquireLock(key, Duration.ofSeconds(30)).orElseThrow();
        // Fabricate a token that did not come from acquireLock.
        IdempotencyStore.LockToken fakeToken = realToken; // We'll re-acquire after force-stealing.

        // Force-steal by waiting + re-acquiring.
        // (Easier path: directly use the realToken's structure to make a wrong one.)
        // For PG, the save() guards on lock_token match, so mocking is enough:
        IdempotencyRecord record = IdempotencyRecord.builder()
            .key(key.value())
            .statusCode(200)
            .body(new byte[]{1, 2, 3})
            .expiresAt(Instant.now().plus(Duration.ofMinutes(5)))
            .build();

        // Save with the right token works.
        store.save(record, realToken);

        // Subsequent save with the SAME (now-released) token must fail since
        // the row's lock_token was nulled by the first save.
        assertThatThrownBy(() -> store.save(record, fakeToken))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("lock token mismatch");
    }

    @Test
    void releaseLock_freesKeyForReacquireWithoutCaching() {
        // The production retry path for non-cacheable statuses and over-cap
        // responses: the filter calls releaseLock instead of save, and the same
        // key must then be lockable again with nothing replayable. Previously
        // only covered on the in-memory store.
        IdempotencyKey key = IdempotencyKey.of("release-001");
        IdempotencyStore.LockToken token = store.acquireLock(key, Duration.ofSeconds(30)).orElseThrow();
        // While the lock is held, a second acquire fails.
        assertThat(store.acquireLock(key, Duration.ofSeconds(30))).isEmpty();

        store.releaseLock(key, token);

        // Nothing was cached, and the key is free to lock again.
        assertThat(store.findRecord(key)).isEmpty();
        assertThat(store.acquireLock(key, Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void findRecord_returnsEmptyForExpiredRecord() throws InterruptedException {
        IdempotencyKey key = IdempotencyKey.of("expired-record-001");
        IdempotencyStore.LockToken token = store.acquireLock(key, Duration.ofSeconds(30)).orElseThrow();
        IdempotencyRecord record = IdempotencyRecord.builder()
            .key(key.value())
            .statusCode(200)
            .body(new byte[]{42})
            .expiresAt(Instant.now().plus(Duration.ofSeconds(1)))
            .build();
        store.save(record, token);

        Thread.sleep(1500);
        assertThat(store.findRecord(key)).isEmpty();
    }

    @Test
    void headersWithSpecialCharacters_roundTripIntact() {
        IdempotencyKey key = IdempotencyKey.of("special-chars-001");
        IdempotencyStore.LockToken token = store.acquireLock(key, Duration.ofSeconds(30)).orElseThrow();

        Map<String, List<String>> headers = Map.of(
            "X-Quote", List.of("value with \"quotes\""),
            "X-Backslash", List.of("path\\to\\file"),
            "Set-Cookie", List.of("session=abc; HttpOnly", "lang=es; Secure")
        );
        IdempotencyRecord record = IdempotencyRecord.builder()
            .key(key.value())
            .statusCode(200)
            .body(new byte[]{1})
            .headers(headers)
            .expiresAt(Instant.now().plus(Duration.ofMinutes(5)))
            .build();
        store.save(record, token);

        IdempotencyRecord replayed = store.findRecord(key).orElseThrow();
        assertThat(replayed.headers().get("X-Quote")).containsExactly("value with \"quotes\"");
        assertThat(replayed.headers().get("X-Backslash")).containsExactly("path\\to\\file");
        assertThat(replayed.headers().get("Set-Cookie"))
            .containsExactly("session=abc; HttpOnly", "lang=es; Secure");
    }

    @Test
    void extendLockSlidesBothClocksPreventingSteal() throws InterruptedException {
        IdempotencyKey key = IdempotencyKey.of("ext-dual");
        IdempotencyStore.LockToken token = store.acquireLock(key, Duration.ofMillis(300)).orElseThrow();

        assertThat(store.supportsLockExtension()).isTrue();
        assertThat(store.extendLock(key, token, Duration.ofSeconds(10))).isTrue();

        // Past the ORIGINAL 300ms window. The extend slid BOTH locked_until and
        // expires_at forward; had it left expires_at behind, the steal predicate's
        // unqualified `OR expires_at < now` branch would steal this live,
        // heartbeat-extended lock — the regression this test guards.
        Thread.sleep(450);
        assertThat(store.acquireLock(key, Duration.ofMillis(300)))
            .as("extended lock is not stealable past its original window")
            .isEmpty();
    }

    @Test
    void extendLockIsTokenChecked() {
        IdempotencyKey a = IdempotencyKey.of("ext-a");
        IdempotencyKey b = IdempotencyKey.of("ext-b");
        store.acquireLock(a, Duration.ofSeconds(1)).orElseThrow();
        IdempotencyStore.LockToken tokenB = store.acquireLock(b, Duration.ofSeconds(1)).orElseThrow();

        assertThat(store.extendLock(a, tokenB, Duration.ofSeconds(10)))
            .as("a token for another key cannot extend this lock")
            .isFalse();
    }
}
