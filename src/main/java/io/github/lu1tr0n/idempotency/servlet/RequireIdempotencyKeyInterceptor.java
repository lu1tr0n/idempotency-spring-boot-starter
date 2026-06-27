package io.github.lu1tr0n.idempotency.servlet;

import io.github.lu1tr0n.idempotency.annotation.RequireIdempotencyKey;
import io.github.lu1tr0n.idempotency.autoconfigure.IdempotencyProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces {@link RequireIdempotencyKey}: a request that reaches a handler
 * marked (on the method or its controller class) as requiring the key, without
 * supplying a present, non-blank {@code Idempotency-Key} header, is rejected
 * with {@code 400 Bad Request} and code {@code IDEMPOTENCY_KEY_REQUIRED}.
 *
 * <h2>Why an interceptor, not the filter</h2>
 * <p>The global {@link IdempotencyFilter} runs <em>before</em> Spring MVC
 * resolves which handler serves the request, so it cannot see the annotation.
 * A {@code HandlerInterceptor} runs inside the {@code DispatcherServlet} with
 * the resolved {@link HandlerMethod} in hand, downstream of every servlet
 * filter (Spring Security, the idempotency filter). It only ever fires on the
 * key-absent path — when the key is present the filter already owns tracking,
 * and a present-but-malformed key was already rejected by the filter before the
 * dispatcher ran. The two response writers are therefore never both reached for
 * one request.
 *
 * <h2>Validity</h2>
 * <p>"Required" is presence only: the header must be non-{@code null} and
 * non-blank. Grammar validation ({@code IdempotencyKey.of}) stays in the filter
 * to avoid a double-validation race.
 */
public class RequireIdempotencyKeyInterceptor implements HandlerInterceptor {

    static final String ERROR_CODE = "IDEMPOTENCY_KEY_REQUIRED";

    private final IdempotencyProperties properties;

    /**
     * Memo of the (method or declaring-class) annotation lookup, so the
     * reflective {@code AnnotatedElementUtils} scan does not run on every
     * request to an annotated handler. Bounded by the number of distinct
     * handler methods in the application.
     *
     * <p>The key is the {@code (beanType, method)} pair, not the {@code Method}
     * alone: an inherited handler method declared on an abstract base controller
     * is a single {@code Method} object shared by every subclass, but the
     * requirement depends on the concrete controller's class-level annotation.
     * Keying on the method alone would let the first request decide the answer
     * for all siblings — silently dropping the requirement on an annotated
     * controller that happens to be hit second.
     */
    private final Map<CacheKey, Boolean> requirementCache = new ConcurrentHashMap<>();

    private record CacheKey(Class<?> beanType, Method method) {
    }

    public RequireIdempotencyKeyInterceptor(IdempotencyProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            // Resource handlers, functional (RouterFunction) endpoints, etc.
            // cannot carry the annotation.
            return true;
        }
        if (!requiresKey(handlerMethod)) {
            return true;
        }
        String headerValue = request.getHeader(properties.getHeaderName());
        if (headerValue != null && !headerValue.isBlank()) {
            // Present → the filter owns tracking; nothing to enforce here.
            return true;
        }
        // No key to vary on, so the rejection carries no idempotency semantics
        // and must not be reused for a later (possibly keyed) retry.
        response.setHeader("Cache-Control", "no-store");
        // Static message — never reflect the (absent/attacker-controlled) header
        // value into the body. The header name comes from trusted config.
        IdempotencyHttpErrors.write(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_CODE,
            "The " + properties.getHeaderName() + " request header is required for this endpoint.");
        return false;
    }

    private boolean requiresKey(HandlerMethod handlerMethod) {
        CacheKey cacheKey = new CacheKey(handlerMethod.getBeanType(), handlerMethod.getMethod());
        return requirementCache.computeIfAbsent(cacheKey, k ->
            AnnotatedElementUtils.hasAnnotation(k.method(), RequireIdempotencyKey.class)
                || AnnotatedElementUtils.hasAnnotation(k.beanType(), RequireIdempotencyKey.class));
    }
}
