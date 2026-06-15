package io.github.lu1tr0n.idempotency.aop;

import io.github.lu1tr0n.idempotency.annotation.Idempotent;
import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyProperties;
import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyKeyResolver;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;

import org.aopalliance.intercept.MethodInvocation;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * AOP aspect that implements {@link Idempotent} semantics around annotated
 * controller methods.
 *
 * <p>The global servlet filter handles every {@code POST}/{@code PUT}/{@code PATCH}
 * with an {@code Idempotency-Key} header transparently — most users will never
 * need this aspect. It exists for three things the filter cannot do alone:
 *
 * <ol>
 *   <li><strong>SpEL key derivation</strong> — when the key lives inside the
 *       request body or path variables ({@code @Idempotent(key = "#request.orderId")}),
 *       the filter cannot resolve it because the body has not yet been
 *       deserialised. The aspect runs after Spring MVC binds arguments.</li>
 *   <li><strong>Per-method TTL override</strong> — {@code @Idempotent(ttl = 5, timeUnit = MINUTES)}
 *       changes the cache window for a single endpoint without touching the
 *       global property.</li>
 *   <li><strong>Per-method opt-out</strong> — {@code @Idempotent(enabled = false)}
 *       lets a single endpoint bypass tracking even when the global filter
 *       would otherwise apply.</li>
 * </ol>
 *
 * <p><strong>Coordination with the global filter:</strong> the aspect is best
 * paired with the filter's annotation awareness — when both run, the filter
 * skips methods marked {@code @Idempotent} so the aspect owns the decision
 * unambiguously. Without that coordination, the filter caches first (using
 * the global TTL + header-only key) and the aspect's body is a no-op on
 * replay. Either composition is correct; the coordinated path avoids a
 * pointless second store round-trip.
 *
 * <p>Async return types ({@link CompletableFuture}, reactive types) are
 * handled in {@link #attachAsyncCompletion(IdempotencyKey, String,
 * IdempotencyStore.LockToken, Object)} — the aspect snapshots the eventual
 * value, not the wrapper.
 */
@Aspect
public class IdempotentAspect implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final IdempotentKeyResolver keyResolver;
    private final IdempotentExpressionEvaluator expressionEvaluator;
    private final int order;

    public IdempotentAspect(IdempotencyStore store,
                            IdempotencyProperties properties,
                            IdempotentKeyResolver keyResolver) {
        this.store = store;
        this.properties = properties;
        this.keyResolver = keyResolver;
        this.expressionEvaluator = new IdempotentExpressionEvaluator();
        // Run BEFORE @Transactional (Ordered.LOWEST_PRECEDENCE) so a cache hit
        // short-circuits the transactional bean method without opening a tx.
        this.order = Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Around("@annotation(io.github.lu1tr0n.idempotency.annotation.Idempotent)")
    public Object aroundIdempotent(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Idempotent annotation = method.getAnnotation(Idempotent.class);

        if (annotation == null || !annotation.enabled()) {
            return joinPoint.proceed();
        }

        String resolvedKey = resolveKey(annotation, joinPoint, signature);
        if (resolvedKey == null || resolvedKey.isBlank()) {
            // No key available — run the method as-is; nothing to cache against.
            return joinPoint.proceed();
        }

        IdempotencyKey key;
        try {
            key = IdempotencyKey.of(resolvedKey);
        } catch (IllegalArgumentException ex) {
            log.warn("@Idempotent on {}#{} produced an invalid key '{}': {}",
                method.getDeclaringClass().getSimpleName(), method.getName(),
                truncateForLog(resolvedKey), ex.getMessage());
            return joinPoint.proceed();
        }

        // Existing record → replay.
        Optional<IdempotencyRecord> existing = safeFindRecord(key);
        if (existing.isPresent()) {
            log.debug("@Idempotent replay: key={} on {}#{}",
                key.value(), method.getDeclaringClass().getSimpleName(), method.getName());
            return replayValue(existing.get(), method);
        }

        // Acquire lock. On contention we let the method run anyway — the
        // collision response that the servlet filter returns (409) is HTTP-
        // specific; for an annotation that wraps any method (not just web
        // endpoints) we cannot assume an HTTP transport.
        Optional<IdempotencyStore.LockToken> lock = safeAcquireLock(key);
        if (lock.isEmpty()) {
            log.debug("@Idempotent could not acquire lock for key={} on {}#{}; proceeding without dedup",
                key.value(), method.getDeclaringClass().getSimpleName(), method.getName());
            return joinPoint.proceed();
        }

        IdempotencyStore.LockToken token = lock.get();
        Duration ttl = effectiveTtl(annotation);

        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable ex) {
            // Failure path — release the lock so a retry can re-attempt.
            try { store.releaseLock(key, token); } catch (RuntimeException ignored) { }
            throw ex;
        }

        // Async types: attach a completion hook that snapshots the eventual
        // value once available. Sync types: snapshot immediately.
        return attachAsyncCompletion(key, resolvedKey, token, result, method.getReturnType(), ttl);
    }

    private String resolveKey(Idempotent annotation, ProceedingJoinPoint joinPoint, MethodSignature signature) {
        // 1. SpEL expression takes precedence.
        if (!annotation.key().isBlank()) {
            try {
                String spelKey = expressionEvaluator.evaluate(
                    annotation.key(), signature, joinPoint.getTarget(), joinPoint.getArgs());
                if (spelKey != null) return spelKey;
            } catch (RuntimeException ex) {
                log.warn("@Idempotent SpEL `{}` on {}#{} failed: {}",
                    annotation.key(),
                    signature.getMethod().getDeclaringClass().getSimpleName(),
                    signature.getMethod().getName(),
                    ex.getMessage());
                return null;
            }
        }
        // 2. Header-based resolver — only meaningful in an HTTP context. The
        //    resolver returns Optional.empty() outside an HTTP request.
        if (keyResolver != null) {
            Optional<String> headerKey = keyResolver.resolveFromAmbientRequest(properties.getHeaderName());
            if (headerKey.isPresent()) return headerKey.get();
        }
        return null;
    }

    private Duration effectiveTtl(Idempotent annotation) {
        if (annotation.ttl() <= 0) return properties.getTtl();
        return Duration.ofMillis(annotation.timeUnit().toMillis(annotation.ttl()));
    }

    private Optional<IdempotencyRecord> safeFindRecord(IdempotencyKey key) {
        try {
            return store.findRecord(key);
        } catch (IdempotencyStore.StoreException ex) {
            log.warn("@Idempotent store unreachable on findRecord(key={}): {}", key.value(), ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<IdempotencyStore.LockToken> safeAcquireLock(IdempotencyKey key) {
        try {
            return store.acquireLock(key, properties.getLockTimeout());
        } catch (IdempotencyStore.StoreException ex) {
            log.warn("@Idempotent store unreachable on acquireLock(key={}): {}", key.value(), ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Snapshot the method's result into the store. For sync types the value
     * is captured immediately. For async types we hook the completion stage
     * and capture once the eventual value is available.
     */
    private Object attachAsyncCompletion(IdempotencyKey key, String rawKey,
                                         IdempotencyStore.LockToken token,
                                         Object result, Class<?> returnType, Duration ttl) {
        if (result == null) {
            persist(key, null, ttl, token);
            return null;
        }
        if (result instanceof CompletionStage<?> stage) {
            return stage.whenComplete((value, ex) -> {
                if (ex != null) {
                    safeReleaseLock(key, token);
                } else {
                    persist(key, value, ttl, token);
                }
            });
        }
        // Reactor Mono — handled reflectively so we don't hard-depend on
        // reactor-core when async support is unused.
        if (isReactorMono(result)) {
            return wrapMono(key, token, result, ttl);
        }
        persist(key, result, ttl, token);
        return result;
    }

    private void persist(IdempotencyKey key, Object value, Duration ttl, IdempotencyStore.LockToken token) {
        try {
            byte[] body = AnnotationValueCodec.serialise(value);
            IdempotencyRecord record = IdempotencyRecord.builder()
                .key(key.value())
                .statusCode(200)
                .body(body)
                .contentType("application/x-idempotent-annotation")
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(ttl))
                .build();
            store.save(record, token);
        } catch (IdempotencyStore.StoreException ex) {
            log.warn("@Idempotent failed to persist record for key={}: {}", key.value(), ex.getMessage());
            safeReleaseLock(key, token);
        } catch (RuntimeException ex) {
            log.warn("@Idempotent failed to serialise result for key={}: {}", key.value(), ex.getMessage());
            safeReleaseLock(key, token);
        }
    }

    private void safeReleaseLock(IdempotencyKey key, IdempotencyStore.LockToken token) {
        try { store.releaseLock(key, token); } catch (RuntimeException ignored) { }
    }

    private Object deserialiseValue(IdempotencyRecord record, Class<?> targetType) {
        return AnnotationValueCodec.deserialise(record.body(), targetType);
    }

    /**
     * Replay a cached record honouring the method's declared return type.
     * Sync types → the value itself. {@link CompletionStage} → a completed
     * future. Reactor {@code Mono} → a Mono.just(value).
     */
    private Object replayValue(IdempotencyRecord record, Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }
        Class<?> targetType = unwrapAsyncReturnType(method);
        Object value = record.body() == null ? null
            : AnnotationValueCodec.deserialise(record.body(), targetType);

        if (CompletionStage.class.isAssignableFrom(returnType)) {
            return CompletableFuture.completedFuture(value);
        }
        if ("reactor.core.publisher.Mono".equals(returnType.getName())) {
            return ReactorMonoBridge.just(value);
        }
        return value;
    }

    /**
     * Returns the {@code T} of a parameterised return type {@code Wrapper<T>}
     * (CompletableFuture, Mono, etc.). Falls back to {@code Object.class} when
     * the parameter is unknown — Jackson will use the JSON shape to pick a
     * sensible default ({@code Map<String,Object>}).
     */
    private Class<?> unwrapAsyncReturnType(Method method) {
        Class<?> returnType = method.getReturnType();
        boolean isAsync = CompletionStage.class.isAssignableFrom(returnType)
            || "reactor.core.publisher.Mono".equals(returnType.getName())
            || "reactor.core.publisher.Flux".equals(returnType.getName());
        if (!isAsync) return returnType;

        java.lang.reflect.Type generic = method.getGenericReturnType();
        if (generic instanceof java.lang.reflect.ParameterizedType pt) {
            java.lang.reflect.Type arg = pt.getActualTypeArguments()[0];
            if (arg instanceof Class<?> c) return c;
            if (arg instanceof java.lang.reflect.ParameterizedType ptp && ptp.getRawType() instanceof Class<?> rc) {
                return rc;
            }
        }
        return Object.class;
    }

    private boolean isReactorMono(Object result) {
        // Avoid a hard reactor-core dependency: the class name comparison is
        // enough; the wrapper method below resolves the type via reflection.
        return "reactor.core.publisher.Mono".equals(result.getClass().getName())
            || isAssignableTo(result.getClass(), "reactor.core.publisher.Mono");
    }

    private boolean isAssignableTo(Class<?> type, String fqn) {
        Class<?> current = type;
        while (current != null) {
            if (fqn.equals(current.getName())) return true;
            for (Class<?> iface : current.getInterfaces()) {
                if (fqn.equals(iface.getName())) return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private Object wrapMono(IdempotencyKey key, IdempotencyStore.LockToken token, Object mono, Duration ttl) {
        // Defer to a thin reflection-based wrapper rather than importing
        // reactor-core at compile time. The aspect itself does not need to
        // resolve the Mono publisher — we just chain a side-effect terminator.
        try {
            return ReactorMonoBridge.wrap(mono, (value, ex) -> {
                if (ex != null) {
                    safeReleaseLock(key, token);
                } else {
                    persist(key, value, ttl, token);
                }
            });
        } catch (RuntimeException ex) {
            log.warn("@Idempotent Mono completion hook failed for key={}: {}", key.value(), ex.getMessage());
            persist(key, mono, ttl, token);
            return mono;
        }
    }

    private static String truncateForLog(String s) {
        return s.length() > 64 ? s.substring(0, 61) + "..." : s;
    }

    @Override
    public int getOrder() {
        return order;
    }

    /**
     * Optional helper invoked by tests + the WebFlux filter to verify the
     * advice path is reachable. Not part of the public API.
     */
    public boolean isReady() {
        return store != null && properties != null;
    }
}
