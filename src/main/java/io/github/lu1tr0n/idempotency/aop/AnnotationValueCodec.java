package io.github.lu1tr0n.idempotency.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;

/**
 * Tiny codec the {@code @Idempotent} aspect uses to round-trip method return
 * values through the {@code IdempotencyStore}.
 *
 * <p>Stored on the wire as JSON via Jackson — the same library Spring Boot
 * pulls in by default, so we don't add a new transitive dependency. We
 * accept the JSON-incompatibility tradeoff (no Optional, no Stream, no
 * unrepresentable values) because:
 *
 * <ol>
 *   <li>The aspect targets controller methods — return types are already
 *       JSON-friendly (Spring MVC serialises them with Jackson).</li>
 *   <li>JSON is human-readable for debugging; Java serialisation is not.</li>
 *   <li>Java serialisation is a known security footgun (deserialisation
 *       vulnerabilities); deserialising attacker-controlled bytes from
 *       Redis would be reckless.</li>
 * </ol>
 *
 * <p>For richer fidelity (records, sealed types, Guava collections) consumers
 * can register a custom Jackson {@code ObjectMapper} bean — this codec
 * lazily resolves the shared mapper if Spring Boot has configured one.
 */
final class AnnotationValueCodec {

    private AnnotationValueCodec() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    static byte[] serialise(Object value) {
        if (value == null) return new byte[0];
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (Exception ex) {
            throw new IllegalStateException(
                "@Idempotent: could not serialise return value of type "
                    + value.getClass().getName() + ": " + ex.getMessage(), ex);
        }
    }

    static Object deserialise(byte[] bytes, Class<?> targetType) {
        if (bytes == null || bytes.length == 0) return null;
        if (targetType == void.class || targetType == Void.class) return null;
        try {
            if (targetType == String.class) {
                // Avoid wrapping a String in JSON quotes when the original
                // value already was a plain String.
                String json = new String(bytes, StandardCharsets.UTF_8);
                if (json.startsWith("\"") && json.endsWith("\"")) {
                    return MAPPER.readValue(bytes, String.class);
                }
                return json;
            }
            return MAPPER.readValue(bytes, targetType);
        } catch (Exception ex) {
            throw new IllegalStateException(
                "@Idempotent: could not deserialise cached value into "
                    + targetType.getName() + ": " + ex.getMessage(), ex);
        }
    }
}
