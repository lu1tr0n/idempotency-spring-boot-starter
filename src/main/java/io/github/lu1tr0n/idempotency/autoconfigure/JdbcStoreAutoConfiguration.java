package io.github.lu1tr0n.idempotency.autoconfigure;

import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.store.jdbc.JdbcIdempotencyStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import javax.sql.DataSource;

/**
 * Wires the JDBC-backed {@link IdempotencyStore} when a {@link JdbcTemplate}
 * is available AND the user did not select a different backend explicitly.
 *
 * <p>The "backend selection" condition handles three cases:
 *
 * <ul>
 *   <li>{@code spring.idempotency.backend=jdbc} → always activates here.</li>
 *   <li>{@code spring.idempotency.backend=auto} (default) → activates here
 *       only if no Redis store has been registered.</li>
 *   <li>{@code spring.idempotency.backend=redis|in-memory} → does not activate.</li>
 * </ul>
 *
 * <p>The user can always override by providing their own
 * {@code IdempotencyStore} bean, which makes {@code @ConditionalOnMissingBean}
 * back off and ours stays dormant.
 */
@AutoConfiguration(after = org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
@ConditionalOnClass({JdbcTemplate.class, DataSource.class})
@ConditionalOnBean(DataSource.class)
@Conditional(JdbcStoreAutoConfiguration.JdbcBackendSelected.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class JdbcStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JdbcStoreAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore jdbcIdempotencyStore(JdbcTemplate jdbcTemplate, IdempotencyProperties properties) {
        log.info("Idempotency: using JDBC backend with table '{}'", properties.getJdbc().getTableName());
        return new JdbcIdempotencyStore(jdbcTemplate, properties.getJdbc().getTableName());
    }

    /**
     * Optional convenience: when {@code spring.idempotency.jdbc.auto-create-table=true},
     * runs the appropriate platform-specific schema at startup. Intended for
     * tests and local dev; production should let Flyway / Liquibase own
     * schema lifecycle.
     *
     * <p>Platform detection: when {@code spring.idempotency.jdbc.platform=AUTO}
     * (the default), the bootstrap inspects {@code DatabaseMetaData.getDatabaseProductName()}
     * once at startup and picks {@code schema-postgres.sql} for PostgreSQL,
     * {@code schema-h2.sql} for H2, and falls back to H2 for HSQLDB (which
     * shares enough syntax). Override with an explicit
     * {@code spring.idempotency.jdbc.platform} value for less-common drivers.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "spring.idempotency.jdbc", name = "auto-create-table", havingValue = "true")
    static class SchemaBootstrap {
        private static final Logger schemaLog = LoggerFactory.getLogger(SchemaBootstrap.class);

        @Bean
        public DataSourceInitializer idempotencySchemaInitializer(DataSource dataSource, IdempotencyProperties properties) {
            String script = pickScript(dataSource, properties.getJdbc().getPlatform());
            schemaLog.info("Idempotency: bootstrapping schema from {}", script);
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource(script));
            populator.setContinueOnError(false);
            DataSourceInitializer initializer = new DataSourceInitializer();
            initializer.setDataSource(dataSource);
            initializer.setDatabasePopulator(populator);
            return initializer;
        }

        private String pickScript(DataSource dataSource, IdempotencyProperties.Jdbc.Platform platform) {
            IdempotencyProperties.Jdbc.Platform resolved = platform;
            if (resolved == IdempotencyProperties.Jdbc.Platform.AUTO) {
                resolved = detectPlatform(dataSource);
            }
            return switch (resolved) {
                case POSTGRES -> "io/github/lu1tr0n/idempotency/jdbc/schema-postgres.sql";
                case H2, HSQLDB, AUTO -> "io/github/lu1tr0n/idempotency/jdbc/schema-h2.sql";
            };
        }

        private IdempotencyProperties.Jdbc.Platform detectPlatform(DataSource dataSource) {
            try (var conn = dataSource.getConnection()) {
                String product = conn.getMetaData().getDatabaseProductName();
                String n = product == null ? "" : product.toLowerCase(java.util.Locale.ROOT);
                if (n.contains("postgres")) return IdempotencyProperties.Jdbc.Platform.POSTGRES;
                if (n.contains("h2"))       return IdempotencyProperties.Jdbc.Platform.H2;
                if (n.contains("hsql"))     return IdempotencyProperties.Jdbc.Platform.HSQLDB;
                schemaLog.warn("Idempotency: unrecognised database product '{}'; defaulting to H2 schema flavour. Set spring.idempotency.jdbc.platform to override.", product);
                return IdempotencyProperties.Jdbc.Platform.H2;
            } catch (java.sql.SQLException e) {
                schemaLog.warn("Idempotency: failed to read database product name; defaulting to H2 schema flavour.", e);
                return IdempotencyProperties.Jdbc.Platform.H2;
            }
        }
    }

    /**
     * Custom condition: pass when {@code spring.idempotency.backend} is
     * either {@code JDBC} or {@code AUTO}. {@code @ConditionalOnProperty}
     * alone cannot express OR semantics across an enum.
     */
    static class JdbcBackendSelected implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String raw = context.getEnvironment().getProperty("spring.idempotency.backend", "AUTO");
            String normalised = raw.replace('-', '_').toUpperCase(java.util.Locale.ROOT);
            return "JDBC".equals(normalised) || "AUTO".equals(normalised);
        }
    }
}
