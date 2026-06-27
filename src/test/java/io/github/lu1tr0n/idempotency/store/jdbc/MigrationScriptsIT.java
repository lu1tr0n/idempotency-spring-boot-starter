package io.github.lu1tr0n.idempotency.store.jdbc;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the shipped Flyway and Liquibase migration artifacts produce a schema
 * the {@link JdbcIdempotencyStore} works against — i.e. the migrations stay in
 * lock-step with what the store reads and writes — and that nothing ships on an
 * auto-discovered migration path (which would hijack a consumer's pipeline).
 */
@Testcontainers
class MigrationScriptsIT {

    private static final String FLYWAY_LOCATION = "classpath:db/idempotency/flyway/postgresql";
    private static final String LIQUIBASE_CHANGELOG = "db/idempotency/liquibase/db.changelog-idempotency.sql";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("idempotency_test")
            .withUsername("test")
            .withPassword("test");

    DataSource ds;
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanSchema() {
        ds = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        // Order-independent: each test applies its own migration from scratch.
        jdbc.execute("DROP TABLE IF EXISTS idempotency_records");
        jdbc.execute("DROP TABLE IF EXISTS flyway_schema_history");
        jdbc.execute("DROP TABLE IF EXISTS databasechangelog");
        jdbc.execute("DROP TABLE IF EXISTS databasechangeloglock");
    }

    @Test
    void flywayMigration_producesStoreCompatibleSchema() {
        Flyway.configure()
            .dataSource(ds)
            .locations(FLYWAY_LOCATION)
            .load()
            .migrate();

        assertStoreRoundTrips();
    }

    @Test
    void liquibaseChangelog_producesStoreCompatibleSchema() throws Exception {
        try (Connection conn = ds.getConnection()) {
            Database db = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            try (Liquibase liquibase = new Liquibase(
                LIQUIBASE_CHANGELOG, new ClassLoaderResourceAccessor(), db)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }

        assertStoreRoundTrips();
    }

    /** Exercise the lock → save → replay path against the freshly-migrated schema. */
    private void assertStoreRoundTrips() {
        IdempotencyStore store = new JdbcIdempotencyStore(jdbc, "idempotency_records");
        IdempotencyKey key = IdempotencyKey.of("migration-rt-001");

        Optional<IdempotencyStore.LockToken> token = store.acquireLock(key, Duration.ofSeconds(30));
        assertThat(token).isPresent();
        // While the lock is held, a concurrent lookup sees nothing (lock_token IS NULL guard).
        assertThat(store.findRecord(key)).isEmpty();

        IdempotencyRecord record = IdempotencyRecord.builder()
            .key(key.value())
            .statusCode(201)
            .body("{\"id\":\"order-1\"}".getBytes(StandardCharsets.UTF_8))
            .contentType("application/json")
            .payloadHash("abc123")
            .addHeader("Location", "/orders/order-1")
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(Duration.ofHours(1)))
            .build();
        store.save(record, token.get());

        IdempotencyRecord replayed = store.findRecord(key).orElseThrow();
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(new String(replayed.body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"order-1\"}");
        assertThat(replayed.headers().get("Location")).containsExactly("/orders/order-1");
    }
}
