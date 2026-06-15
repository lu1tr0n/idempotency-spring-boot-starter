package io.github.lu1tr0n.idempotency.aop;

import java.lang.reflect.Method;
import java.util.function.BiConsumer;

/**
 * Reflection-only bridge to Reactor's {@code Mono.doOnEach} / {@code doOnSuccess}
 * / {@code doOnError} so the {@code @Idempotent} aspect can hook a
 * completion side-effect on Mono returns without importing
 * {@code reactor.core.publisher.Mono} at compile time.
 *
 * <p>Consumers who never return a {@code Mono<?>} from a {@code @Idempotent}
 * method never load this class — the aspect's branch only enters when the
 * concrete return is a Mono (detected by FQN comparison).
 */
final class ReactorMonoBridge {

    private ReactorMonoBridge() {
    }

    /**
     * Wrap a Mono so {@code onComplete} fires after success with the emitted
     * value, or after error with the throwable.
     *
     * @return the chained Mono (Mono.doOnSuccess(...).doOnError(...)).
     */
    /**
     * Reflective equivalent of {@code Mono.just(value)} for the replay path
     * when a {@code @Idempotent} method returns {@code Mono<T>}.
     */
    static Object just(Object value) {
        try {
            Class<?> monoClass = Class.forName("reactor.core.publisher.Mono");
            return monoClass.getMethod("justOrEmpty", Object.class).invoke(null, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                "@Idempotent: Mono replay requires reactor-core on the classpath: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object wrap(Object mono, BiConsumer<Object, Throwable> onComplete) {
        try {
            Class<?> monoClass = mono.getClass();
            Class<?> consumerClass = java.util.function.Consumer.class;

            Method doOnSuccess = findMethod(monoClass, "doOnSuccess", consumerClass);
            Method doOnError = findMethod(monoClass, "doOnError", consumerClass);

            java.util.function.Consumer successConsumer = value -> onComplete.accept(value, null);
            java.util.function.Consumer errorConsumer = ex -> onComplete.accept(null, (Throwable) ex);

            Object chained = doOnSuccess.invoke(mono, successConsumer);
            return doOnError.invoke(chained, errorConsumer);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                "@Idempotent: failed to attach completion to Mono: " + ex.getMessage(), ex);
        }
    }

    private static Method findMethod(Class<?> declaring, String name, Class<?> paramType) throws NoSuchMethodException {
        Class<?> current = declaring;
        while (current != null) {
            try {
                return current.getMethod(name, paramType);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(declaring.getName() + "." + name + "(" + paramType.getName() + ")");
    }
}
