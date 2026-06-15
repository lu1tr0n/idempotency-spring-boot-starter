package io.github.lu1tr0n.idempotency.autoconfigure;

import io.github.lu1tr0n.idempotency.aop.IdempotentAspect;
import io.github.lu1tr0n.idempotency.aop.IdempotentKeyResolver;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;

import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.util.Optional;

/**
 * Wires the {@code @Idempotent} aspect when Spring AOP + AspectJ are on the
 * classpath AND an {@link IdempotencyStore} bean is registered.
 *
 * <p>Loads AFTER the in-memory / JDBC / Redis store auto-configs so the
 * {@code @ConditionalOnBean(IdempotencyStore.class)} on the aspect bean
 * evaluates against an already-registered store. (See lab finding #6/#7
 * for the bug this guard prevents.)
 */
@AutoConfiguration(after = {
    InMemoryStoreAutoConfiguration.class,
    JdbcStoreAutoConfiguration.class,
    RedisStoreAutoConfiguration.class
})
@ConditionalOnClass({Aspect.class, org.aspectj.lang.ProceedingJoinPoint.class})
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAopAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAopAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public IdempotentKeyResolver idempotentKeyResolver() {
        // Try the servlet-stack request resolver first; fall back to the
        // no-op resolver when spring-web is not on the classpath.
        try {
            Class<?> servletRequestClass = Class.forName(
                "org.springframework.web.context.request.RequestContextHolder");
            return new ServletAmbientKeyResolver(servletRequestClass);
        } catch (ClassNotFoundException ex) {
            log.debug("spring-web not on classpath; @Idempotent header lookup disabled");
            return IdempotentKeyResolver.NO_OP;
        }
    }

    @Bean
    @ConditionalOnBean(IdempotencyStore.class)
    @ConditionalOnMissingBean
    public IdempotentAspect idempotentAspect(IdempotencyStore store,
                                             IdempotencyProperties properties,
                                             IdempotentKeyResolver keyResolver) {
        log.info("@Idempotent aspect enabled (header resolver: {})",
            keyResolver == IdempotentKeyResolver.NO_OP ? "none" : "servlet");
        return new IdempotentAspect(store, properties, keyResolver);
    }

    /**
     * Resolver that reads from the ambient servlet request via
     * {@code RequestContextHolder}. Loaded reflectively so this auto-config
     * compiles without a hard spring-web dependency.
     */
    private static final class ServletAmbientKeyResolver implements IdempotentKeyResolver {
        private final Class<?> requestContextHolder;

        ServletAmbientKeyResolver(Class<?> requestContextHolder) {
            this.requestContextHolder = requestContextHolder;
        }

        @Override
        public Optional<String> resolveFromAmbientRequest(String headerName) {
            try {
                Object attrs = requestContextHolder
                    .getMethod("getRequestAttributes")
                    .invoke(null);
                if (attrs == null) return Optional.empty();
                Class<?> servletWebReq = Class.forName(
                    "org.springframework.web.context.request.ServletRequestAttributes");
                if (!servletWebReq.isInstance(attrs)) return Optional.empty();
                Object req = servletWebReq.getMethod("getRequest").invoke(attrs);
                Object value = req.getClass().getMethod("getHeader", String.class).invoke(req, headerName);
                if (value == null) return Optional.empty();
                String s = value.toString();
                return s.isBlank() ? Optional.empty() : Optional.of(s);
            } catch (ReflectiveOperationException ex) {
                return Optional.empty();
            }
        }
    }
}
