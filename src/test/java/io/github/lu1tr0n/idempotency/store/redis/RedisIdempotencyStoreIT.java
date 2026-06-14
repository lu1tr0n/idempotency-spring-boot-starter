package io.github.lu1tr0n.idempotency.store.redis;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests against a real Redis 7 container. Covers the
 * atomicity guarantees the in-memory store cannot prove: SET NX semantics
 * across simulated concurrent callers, the save Lua script's
 * verify-token-and-set-and-del under contention, and TTL-driven lock expiry.
 *
 * <p>Skipped in environments without Docker.
 */
@Testcontainers
class RedisIdempotencyStoreIT {

    @Container
    static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    LettuceConnectionFactory connectionFactory;
    StringRedisTemplate template;
    IdempotencyStore store;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(
            REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(cfg);
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        // Flush per test for isolation.
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
        store = new RedisIdempotencyStore(template, "test:");
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void saveAndReplay_roundTripsFullResponseSurface() {
        IdempotencyKey key = IdempotencyKey.of("rt-001");
        IdempotencyStore.LockToken token = store.acquireLock(key, Duration.ofSeconds(30)).orElseThrow();

        IdempotencyRecord record = IdempotencyRecord.builder()
            .key(key.value())
            .statusCode(201)
            .body("{\"id\":\"order-1\"}".getBytes(StandardCharsets.UTF_8))
            .contentType("application/json")
            .payloadHash("abc123")
            .addHeader("Location", "/orders/order-1")
            .addHeader("X-Trace", "trace-001")
            .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
            .expiresAt(Instant.now().plus(Duration.ofHours(1)))
            .build();
        store.save(record, token);

        IdempotencyRecord replayed = store.findRecord(key).orElseThrow();
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(new String(replayed.body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"order-1\"}");
        assertThat(replayed.contentType()).isEqualTo("application/json");
        assertThat(replayed.payloadHash()).isEqualTo("abc123");
        assertThat(replayed.createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(replayed.headers().get("Location")).containsExactly("/orders/order-1");
    }

    @Test
    void acquireLock_secondCallerSeesEmptyOptional() {
        IdempotencyKey key = IdempotencyKey.of("contention-001");
        assertThat(store.acquireLock(key, Duration.ofSeconds(30))).isPresent();
        assertThat(store.acquireLock(key, Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void acquireLock_canStealExpiredLock() throws InterruptedException {
        IdempotencyKey key = IdempotencyKey.of("expired-001");
        IdempotencyStore.LockToken first = store.acquireLock(key, Duration.ofSeconds(1)).orElseThrow();
        Thread.sleep(1500);

        IdempotencyStore.LockToken second = store.acquireLock(key, Duration.ofSeconds(30)).orElseThrow();
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void save_withStaleTokenIsRejectedAtomically() throws InterruptedException {
        IdempotencyKey key = IdempotencyKey.of("stale-token-001");
        IdempotencyStore.LockToken originalToken = store.acquireLock(key, Duration.ofSeconds(1)).orElseThrow();

        // Wait for the lock to expire, then a different caller acquires it.
        Thread.sleep(1500);
        IdempotencyStore.LockToken stolenToken = store.acquireLock(key, Duration.ofSeconds(30)).orElseThrow();

        // Original caller's late save must fail — Lua script verifies the
        // token matches what's actually in Redis and refuses to corrupt
        // the new owner's lock.
        IdempotencyRecord record = IdempotencyRecord.builder()
            .key(key.value())
            .statusCode(200)
            .body(new byte[]{1})
            .expiresAt(Instant.now().plus(Duration.ofMinutes(5)))
            .build();
        assertThatThrownBy(() -> store.save(record, originalToken))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("lock token mismatch");

        // The new owner's save succeeds.
        store.save(record, stolenToken);
        assertThat(store.findRecord(key)).isPresent();
    }

    @Test
    void findRecord_returnsEmptyForExpiredRecord() throws InterruptedException {
        IdempotencyKey key = IdempotencyKey.of("expired-record-001");
        IdempotencyStore.LockToken token = store.acquireLock(key, Duration.ofSeconds(30)).orElseThrow();
        IdempotencyRecord record = IdempotencyRecord.builder()
            .key(key.value())
            .statusCode(200)
            .body(new byte[]{42})
            .expiresAt(Instant.now().plus(Duration.ofMillis(500)))
            .build();
        store.save(record, token);

        Thread.sleep(1500);
        assertThat(store.findRecord(key)).isEmpty();
    }

    @Test
    void releaseLock_isIdempotent_safeToCallTwice() {
        IdempotencyKey key = IdempotencyKey.of("idempotent-release-001");
        IdempotencyStore.LockToken token = store.acquireLock(key, Duration.ofSeconds(30)).orElseThrow();

        store.releaseLock(key, token);
        store.releaseLock(key, token); // Second call must not throw.

        // Lock is gone — a new caller can acquire it fresh.
        assertThat(store.acquireLock(key, Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void headersWithSpecialCharacters_roundTripIntact() {
        IdempotencyKey key = IdempotencyKey.of("special-headers-001");
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
}
