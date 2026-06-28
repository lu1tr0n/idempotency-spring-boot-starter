package io.github.lu1tr0n.idempotency.heartbeat;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core proof that lock extension prevents a steal of a still-running handler's
 * lock, and that stopping the heartbeat lets the lock lapse again. Timing-based
 * with generous margins; the in-memory store gives a deterministic clock-free
 * lock so the only timing dependency is {@code Thread.sleep}.
 */
class LockHeartbeatTest {

    @Test
    void heartbeat_keepsLockAlivePastLockTimeout_thenLapsesAfterStop() throws InterruptedException {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyKey key = IdempotencyKey.of("hb-keepalive");
        Duration lockTtl = Duration.ofMillis(400);

        IdempotencyStore.LockToken token = store.acquireLock(key, lockTtl).orElseThrow();
        ScheduledLockHeartbeat heartbeat =
            new ScheduledLockHeartbeat(store, Duration.ofMillis(120), lockTtl, Duration.ofSeconds(30), 1);
        LockHeartbeat.Handle handle = heartbeat.start(key, token);
        try {
            // Well past the original 400ms window: a concurrent caller still can't
            // acquire because the heartbeat keeps sliding the lock forward.
            Thread.sleep(900);
            assertThat(store.acquireLock(key, lockTtl))
                .as("lock held alive by heartbeat past its original TTL")
                .isEmpty();
        } finally {
            handle.stop();
        }

        // With the heartbeat stopped, the lock lapses and a caller can acquire.
        Thread.sleep(600); // > lockTtl
        assertThat(store.acquireLock(key, lockTtl))
            .as("lock lapses once the heartbeat stops")
            .isPresent();
        heartbeat.destroy();
    }

    @Test
    void withoutHeartbeat_lockIsStealableAfterTimeout() throws InterruptedException {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyKey key = IdempotencyKey.of("hb-nohb");
        Duration lockTtl = Duration.ofMillis(300);

        store.acquireLock(key, lockTtl).orElseThrow();
        Thread.sleep(500); // > lockTtl, nobody renews
        assertThat(store.acquireLock(key, lockTtl))
            .as("an un-renewed lock is stealable after its timeout")
            .isPresent();
    }

    @Test
    void extendLock_isTokenChecked() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyKey a = IdempotencyKey.of("hb-a");
        IdempotencyKey b = IdempotencyKey.of("hb-b");
        IdempotencyStore.LockToken tokenA = store.acquireLock(a, Duration.ofSeconds(1)).orElseThrow();
        IdempotencyStore.LockToken tokenB = store.acquireLock(b, Duration.ofSeconds(1)).orElseThrow();

        assertThat(store.supportsLockExtension()).isTrue();
        assertThat(store.extendLock(a, tokenA, Duration.ofSeconds(5))).as("own token extends").isTrue();
        assertThat(store.extendLock(a, tokenB, Duration.ofSeconds(5))).as("foreign token does not").isFalse();

        store.releaseLock(a, tokenA);
        assertThat(store.extendLock(a, tokenA, Duration.ofSeconds(5))).as("released lock not revived").isFalse();
    }

    @Test
    void noopHeartbeat_startReturnsNoopHandle() {
        // The disabled path must be a pure no-op (no scheduler, no throwing).
        LockHeartbeat.Handle handle =
            LockHeartbeat.NOOP.start(IdempotencyKey.of("hb-noop"), new IdempotencyStore.LockToken() {});
        assertThat(handle).isSameAs(LockHeartbeat.Handle.NOOP);
        handle.stop(); // idempotent, does nothing
    }
}
