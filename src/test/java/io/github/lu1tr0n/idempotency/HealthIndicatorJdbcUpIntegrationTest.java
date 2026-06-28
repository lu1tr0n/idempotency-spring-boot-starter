package io.github.lu1tr0n.idempotency;

import io.github.lu1tr0n.idempotency.health.IdempotencyStoreHealthIndicator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Spring context with Actuator + an auto-configured H2 JDBC store and the
 * bundled schema applied. Proves the conditional wiring registers the
 * {@code idempotency} health contributor and that the real
 * {@code SELECT 1 FROM <table> WHERE 1 = 0} probe reports UP against an
 * existing table.
 */
@SpringBootTest(
    classes = HealthIndicatorJdbcUpIntegrationTest.TestApp.class,
    // No web environment needed: the test drives the indicator bean directly,
    // which also sidesteps the actuator+security management filter chain.
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.idempotency.enabled=true",
        "spring.idempotency.backend=jdbc",
        "spring.idempotency.jdbc.auto-create-table=true",
        "spring.idempotency.health.cache-ttl=0" // probe every call, deterministic
    }
)
class HealthIndicatorJdbcUpIntegrationTest {

    @Autowired
    IdempotencyStoreHealthIndicator indicator;

    @Autowired
    HealthContributorRegistry registry;

    @Test
    void indicatorReportsUp_againstExistingTable() {
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("backend", "jdbc")
            .containsEntry("failureStrategy", "fail-closed");
    }

    @Test
    void contributorRegisteredUnderIdempotencyKey() {
        assertThat(registry.getContributor("idempotency")).isNotNull();
    }

    @SpringBootApplication
    static class TestApp {
    }
}
