package io.github.lu1tr0n.idempotency.heartbeat;

import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyAutoConfiguration;
import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.store.InMemoryIdempotencyStore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conditional wiring of the opt-in heartbeat: off by default, active for a
 * supporting store, and safely disabled (no-op) for a store that cannot extend.
 */
class LockHeartbeatAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class));

    @Test
    void disabledByDefault_noHeartbeatBean() {
        runner.withBean(IdempotencyStore.class, InMemoryIdempotencyStore::new)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(LockHeartbeat.class);
            });
    }

    @Test
    void enabledWithSupportingStore_activeHeartbeat() {
        runner.withBean(IdempotencyStore.class, InMemoryIdempotencyStore::new)
            .withPropertyValues("spring.idempotency.lock-extension.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(LockHeartbeat.class);
                assertThat(context.getBean(LockHeartbeat.class)).isInstanceOf(ScheduledLockHeartbeat.class);
            });
    }

    @Test
    void enabledWithNonSupportingStore_disabledToNoop() {
        runner.withBean(IdempotencyStore.class, NonExtendableStore::new)
            .withPropertyValues("spring.idempotency.lock-extension.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(LockHeartbeat.class);
                // Capability check kicked in: no real scheduler, just the no-op.
                assertThat(context.getBean(LockHeartbeat.class)).isNotInstanceOf(ScheduledLockHeartbeat.class);
            });
    }

    /** A custom store that leaves {@code supportsLockExtension()} at its {@code false} default. */
    static class NonExtendableStore implements IdempotencyStore {
        @Override public Optional<IdempotencyRecord> findRecord(IdempotencyKey key) { return Optional.empty(); }
        @Override public Optional<LockToken> acquireLock(IdempotencyKey key, Duration ttl) { return Optional.empty(); }
        @Override public void save(IdempotencyRecord record, LockToken token) { }
        @Override public void releaseLock(IdempotencyKey key, LockToken token) { }
    }
}
