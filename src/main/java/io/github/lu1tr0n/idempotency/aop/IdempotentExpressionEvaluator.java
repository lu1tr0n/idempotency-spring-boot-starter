package io.github.lu1tr0n.idempotency.aop;

import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates SpEL expressions written against {@link io.github.lu1tr0n.idempotency.annotation.Idempotent#key()}.
 *
 * <p>The evaluation context exposes the method's named arguments (so an
 * expression like {@code #request.orderId} resolves against the parameter
 * named {@code request}), plus a few synthetic root variables:
 *
 * <ul>
 *   <li>{@code #method} — the {@link Method} being invoked.</li>
 *   <li>{@code #args} — the array of method arguments.</li>
 *   <li>{@code #target} — the target bean instance.</li>
 * </ul>
 *
 * <p>Expressions are parsed once per {@code (method, expression)} pair and
 * cached. Evaluation contexts are NOT cached — they capture per-invocation
 * arguments.
 */
final class IdempotentExpressionEvaluator {

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ConcurrentHashMap<ExpressionKey, Expression> expressionCache = new ConcurrentHashMap<>(64);
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * Evaluate {@code spel} against {@code joinPoint}'s arguments. Returns the
     * evaluation result coerced to {@code String} — callers use that string as
     * the idempotency key.
     *
     * @return the evaluated key, or {@code null} when the expression evaluates
     *         to {@code null}. An empty string is returned as-is; the caller
     *         decides whether to reject it.
     * @throws IllegalArgumentException when the expression cannot be parsed
     *         (caller wraps this with the offending method's signature).
     */
    String evaluate(String spel, MethodSignature signature, Object target, Object[] args) {
        Expression expression = expressionCache.computeIfAbsent(
            new ExpressionKey(signature.getMethod(), spel),
            k -> parser.parseExpression(k.expression()));

        EvaluationContext context = createContext(signature, target, args);
        Object value = expression.getValue(context);
        return value == null ? null : value.toString();
    }

    private EvaluationContext createContext(MethodSignature signature, Object target, Object[] args) {
        Method method = signature.getMethod();
        StandardEvaluationContext ctx = new StandardEvaluationContext(target);
        ctx.setVariable("method", method);
        ctx.setVariable("args", args);
        ctx.setVariable("target", target);

        // Prefer AspectJ's MethodSignature.getParameterNames() — it reads the
        // names AspectJ recorded at weave time, which survives the -parameters
        // flag being absent from javac. Fall back to Spring's discoverer for
        // edge cases (synthetic methods, bridge methods).
        String[] names = signature.getParameterNames();
        if (names == null || names.length == 0) {
            names = parameterNameDiscoverer.getParameterNames(method);
        }
        if (names != null) {
            for (int i = 0; i < names.length && i < args.length; i++) {
                if (names[i] != null) {
                    ctx.setVariable(names[i], args[i]);
                }
            }
        }
        return ctx;
    }

    /** Cache key — {@code (Method, expressionString)}. */
    private record ExpressionKey(Method method, String expression) {
    }
}
