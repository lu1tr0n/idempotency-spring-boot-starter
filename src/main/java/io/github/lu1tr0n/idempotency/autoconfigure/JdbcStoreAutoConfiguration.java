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
     * runs the bundled H2/Postgres schema at startup. Intended for tests and
     * local dev; production should let Flyway / Liquibase own schema lifecycle.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "spring.idempotency.jdbc", name = "auto-create-table", havingValue = "true")
    static class SchemaBootstrap {
        @Bean
        public DataSourceInitializer idempotencySchemaInitializer(DataSource dataSource) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            // Default to the H2 flavour at startup — works for H2 AND is a
            // strict subset of Postgres-compatible DDL so it won't break the
            // common case where the user runs the auto-create against
            // Postgres for a one-off dev experiment.
            populator.addScript(new ClassPathResource("io/github/lu1tr0n/idempotency/jdbc/schema-h2.sql"));
            populator.setContinueOnError(false);
            DataSourceInitializer initializer = new DataSourceInitializer();
            initializer.setDataSource(dataSource);
            initializer.setDatabasePopulator(populator);
            return initializer;
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
