package io.github.lu1tr0n.idempotency.autoconfigure;

import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.store.redis.RedisIdempotencyStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.util.Locale;

/**
 * Wires the Redis-backed {@link IdempotencyStore} when a
 * {@link StringRedisTemplate} (or a {@link RedisConnectionFactory}) bean is
 * available AND the user did not select a different backend explicitly.
 *
 * <p>Backend selection contract matches {@link JdbcStoreAutoConfiguration}:
 *
 * <ul>
 *   <li>{@code spring.idempotency.backend=redis} → always activates.</li>
 *   <li>{@code spring.idempotency.backend=auto} (default) → activates only
 *       when there is no {@code DataSource} bean on the context. With both
 *       present, JDBC wins; users who want the inverse must set
 *       {@code backend=redis} explicitly.</li>
 *   <li>{@code spring.idempotency.backend=jdbc|in-memory} → does not activate.</li>
 * </ul>
 *
 * <p>The {@code @ConditionalOnMissingBean(IdempotencyStore.class)} guard
 * means a user can always swap in their own store implementation and ours
 * stays dormant.
 */
@AutoConfiguration(after = org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class)
@ConditionalOnClass({StringRedisTemplate.class, RedisConnectionFactory.class})
@ConditionalOnBean(RedisConnectionFactory.class)
@Conditional(RedisStoreAutoConfiguration.RedisBackendSelected.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class RedisStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RedisStoreAutoConfiguration.class);

    /**
     * Provide a {@link StringRedisTemplate} when the user has not declared
     * one — most Spring Boot apps with Redis on the classpath already do,
     * but the {@code spring-boot-starter-data-redis} default only registers
     * a generic {@code RedisTemplate}.
     */
    @Bean
    @ConditionalOnMissingBean(StringRedisTemplate.class)
    public StringRedisTemplate idempotencyStringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    public IdempotencyStore redisIdempotencyStore(StringRedisTemplate stringRedisTemplate,
                                                  IdempotencyProperties properties) {
        log.info("Idempotency: using Redis backend with key prefix '{}'",
            properties.getRedis().getKeyPrefix());
        return new RedisIdempotencyStore(stringRedisTemplate, properties.getRedis().getKeyPrefix());
    }

    /**
     * Pass when {@code spring.idempotency.backend} is {@code REDIS}, or
     * {@code AUTO} with no {@code DataSource} bean available. We have to
     * peek at the bean factory here rather than relying on
     * {@code @ConditionalOnMissingBean(DataSource.class)} because the JDBC
     * auto-config processes its own conditions independently and we want a
     * deterministic tie-breaker when both DataSource and RedisConnectionFactory
     * are present.
     */
    static class RedisBackendSelected implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String raw = context.getEnvironment().getProperty("spring.idempotency.backend", "AUTO");
            String normalised = raw.replace('-', '_').toUpperCase(Locale.ROOT);
            if ("REDIS".equals(normalised)) return true;
            if (!"AUTO".equals(normalised)) return false;
            // AUTO + DataSource present → JDBC wins, we stay out.
            return context.getBeanFactory() == null
                || context.getBeanFactory().getBeanNamesForType(DataSource.class).length == 0;
        }
    }
}
