package io.github.lu1tr0n.idempotency.store.jdbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The crux guard for the migration artifacts: they must ship ONLY on opt-in
 * paths, never where Flyway/Liquibase auto-discover them (which would silently
 * merge into — and collide with — a consumer's migration pipeline). Plain
 * classpath assertions, no container needed.
 */
class MigrationResourcePackagingTest {

    @Test
    void shipsNothingOnAnAutoDiscoveredMigrationPath() {
        ClassLoader cl = getClass().getClassLoader();
        // Flyway's default scan path and Liquibase's conventional master changelogs
        // must be empty.
        assertThat(cl.getResource("db/migration")).isNull();
        assertThat(cl.getResource("db/changelog/db.changelog-master.yaml")).isNull();
        assertThat(cl.getResource("db/changelog/db.changelog-master.xml")).isNull();
        // And our opt-in artifacts are exactly where the docs point consumers.
        assertThat(cl.getResource(
            "db/idempotency/flyway/postgresql/V20260601001__create_idempotency_records.sql")).isNotNull();
        assertThat(cl.getResource("db/idempotency/liquibase/db.changelog-idempotency.sql")).isNotNull();
    }
}
