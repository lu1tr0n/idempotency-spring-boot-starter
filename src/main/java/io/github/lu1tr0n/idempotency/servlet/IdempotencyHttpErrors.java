package io.github.lu1tr0n.idempotency.servlet;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Shared writer for the starter's structured JSON error envelope on the servlet
 * stack. Centralised so {@link IdempotencyFilter} and
 * {@link RequireIdempotencyKeyInterceptor} emit byte-for-byte identical bodies
 * and both perform the response-commit dance that keeps Spring Boot's
 * {@code ErrorPageFilter} from forwarding the 4xx to {@code /error} (which would
 * overwrite the body with the default error page).
 *
 * <p>The envelope is {@code {"error":{"code":"...","message":"..."}}}. The
 * {@code message} is escaped; callers must never pass attacker-controlled values
 * (such as a raw header value) into it.
 */
final class IdempotencyHttpErrors {

    private IdempotencyHttpErrors() {
    }

    /**
     * Write a {@code {"error":{code,message}}} body with the given status and
     * commit the response. Idempotent with respect to commit: if the response
     * is already committed, {@code flushBuffer} is skipped.
     */
    static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + escapeJson(message) + "\"}}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
        // Commit so ErrorPageFilter does not forward a 4xx/5xx to /error and
        // overwrite our structured error body with the default error page.
        if (!response.isCommitted()) {
            response.flushBuffer();
        }
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
