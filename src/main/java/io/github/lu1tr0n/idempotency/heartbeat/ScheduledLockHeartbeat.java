package io.github.lu1tr0n.idempotency.heartbeat;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;

/**
 * {@link LockHeartbeat} backed by a dedicated daemon {@link ThreadPoolTaskScheduler}.
 * Each {@link #start} schedules a fixed-<em>delay</em> renewal (so a slow extend
 * never piles up overlapping beats) that slides the lock window forward by the
 * full lock timeout.
 *
 * <h2>Safety caps</h2>
 * Two backstops stop a runaway heartbeat even if a caller forgets to
 * {@link Handle#stop()}:
 * <ul>
 *   <li><strong>max lifetime</strong> — a beat self-cancels once it has been
 *       running longer than the record TTL; a leaked heartbeat can therefore
 *       never pin a key forever (which would block every retry).</li>
 *   <li><strong>lost-lock</strong> — if {@link IdempotencyStore#extendLock}
 *       reports the token no longer owns the lock, the beat stops and warns that
 *       the operation is now running unprotected.</li>
 * </ul>
 * Renewal is best-effort: a transient {@code StoreException} is logged and the
 * next beat retries; it never escapes onto the scheduler thread or the request.
 */
public final class ScheduledLockHeartbeat implements LockHeartbeat, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ScheduledLockHeartbeat.class);

    private final IdempotencyStore store;
    private final ThreadPoolTaskScheduler scheduler;
    private final Duration interval;
    private final Duration renewTtl;
    private final long maxLifetimeNanos;

    public ScheduledLockHeartbeat(IdempotencyStore store, Duration interval, Duration renewTtl,
                                  Duration maxLifetime, int poolSize) {
        this.store = store;
        this.interval = interval;
        this.renewTtl = renewTtl;
        this.maxLifetimeNanos = maxLifetime.toNanos();
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(Math.max(1, poolSize));
        s.setThreadNamePrefix("idempotency-lock-heartbeat-");
        s.setDaemon(true);
        // Cancelled per-request tasks must leave the delay queue immediately,
        // otherwise high request churn leaks them for up to one interval.
        s.setRemoveOnCancelPolicy(true);
        s.initialize();
        this.scheduler = s;
    }

    @Override
    public Handle start(IdempotencyKey key, IdempotencyStore.LockToken token) {
        final long deadlineNanos = System.nanoTime() + maxLifetimeNanos;
        final ScheduledFuture<?>[] future = new ScheduledFuture<?>[1];
        Runnable beat = () -> beat(key, token, deadlineNanos, future);
        try {
            // First renewal after `interval`, not immediately: acquireLock already
            // set a full lock-timeout window, so an instant beat would be a wasted
            // store write that a fast handler cancels anyway. The initial delay also
            // guarantees future[0] is assigned before any beat can self-reference it.
            future[0] = scheduler.scheduleWithFixedDelay(beat, Instant.now().plus(interval), interval);
        } catch (RejectedExecutionException rejected) {
            // Only realistic during context shutdown. The contract says start() never
            // throws — degrade to no renewal rather than leak the just-acquired lock.
            log.warn("Idempotency lock heartbeat could not be scheduled for key '{}' (scheduler shutting down?); "
                + "proceeding without renewal.", key.value());
            return Handle.NOOP;
        }
        return () -> cancel(future[0]);
    }

    private void beat(IdempotencyKey key, IdempotencyStore.LockToken token,
                      long deadlineNanos, ScheduledFuture<?>[] future) {
        try {
            if (System.nanoTime() - deadlineNanos >= 0) {
                log.warn("Idempotency lock heartbeat for key '{}' reached its max lifetime; stopping renewal. "
                    + "If the handler is still running it now has no idempotency protection.", key.value());
                cancel(future[0]);
                return;
            }
            if (!store.extendLock(key, token, renewTtl)) {
                log.warn("Idempotency lock for key '{}' is no longer held by this request (it likely expired and was "
                    + "re-acquired); stopping heartbeat. The in-flight operation is now running WITHOUT idempotency "
                    + "protection.", key.value());
                cancel(future[0]);
            }
        } catch (RuntimeException ex) {
            // Best-effort: a store blip must not kill the scheduled task or escape the pool thread.
            log.warn("Idempotency lock heartbeat extend failed for key '{}' (retrying next interval): {}",
                key.value(), ex.toString());
        }
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    @Override
    public void destroy() {
        scheduler.destroy();
    }
}
