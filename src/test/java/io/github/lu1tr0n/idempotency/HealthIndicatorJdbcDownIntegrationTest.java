package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.health.IdempotencyStoreHealthIndicator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real H2 JDBC store pointed at a table that was never created (no
 * auto-create), with the default {@code fail-closed} strategy. Proves the
 * real probe SQL genuinely raises on a missing table — the unrun-migration
 * case the generic {@code db} indicator cannot see — and that fail-closed maps
 * it to DOWN.
 */
@SpringBootTest(
    classes = HealthIndicatorJdbcDownIntegrationTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.idempotency.enabled=true",
        "spring.idempotency.backend=jdbc",
        "spring.idempotency.jdbc.table-name=table_that_was_never_migrated",
        "spring.idempotency.jdbc.auto-create-table=false",
        "spring.idempotency.failure-strategy=fail-closed",
        "spring.idempotency.health.cache-ttl=0"
    }
)
class HealthIndicatorJdbcDownIntegrationTest {

    @Autowired
    IdempotencyStoreHealthIndicator indicator;

    @Test
    void missingTable_failClosed_reportsDown() {
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("backend", "jdbc");
    }

    @SpringBootApplication
    static class TestApp {
    }
}
