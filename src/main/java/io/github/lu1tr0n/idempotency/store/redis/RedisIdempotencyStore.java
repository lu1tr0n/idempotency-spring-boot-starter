package io.github.lu1tr0n.idempotency.store.redis;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;
import io.github.lu1tr0n.idempotency.core.IdempotencyStoreHealth;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed {@link IdempotencyStore}, built on {@link StringRedisTemplate}
 * (which works with both Lettuce and Jedis under the same Spring Data Redis
 * abstraction — no transitive client dep is forced).
 *
 * <h2>Key layout</h2>
 *
 * Two keys per logical idempotency key, prefixed by
 * {@code spring.idempotency.redis.key-prefix} (default {@code idempotency:}):
 *
 * <ul>
 *   <li><strong>{@code idempotency:lock:{key}}</strong> — short-TTL string
 *       whose value is the lock token. Created with {@code SET NX PX ttl} so
 *       acquisition is atomic.</li>
 *   <li><strong>{@code idempotency:rec:{key}}</strong> — long-TTL string
 *       containing the JSON-serialised {@link IdempotencyRecord}. Created on
 *       successful save and read on every replay lookup.</li>
 * </ul>
 *
 * <h2>Atomicity</h2>
 *
 * Lock acquisition is a single command ({@code SET NX PX}). Save uses a Lua
 * script ({@link #SAVE_SCRIPT}) that verifies the lock token still belongs
 * to the caller, writes the record, and deletes the lock in one round-trip
 * — preventing a "lock expired and was re-acquired by someone else" race
 * from corrupting the new owner's lock.
 *
 * <h2>Serialisation</h2>
 *
 * Records are encoded as compact JSON (hand-rolled to avoid forcing Jackson
 * on the consumer's classpath). The response body is base64-encoded because
 * arbitrary bytes do not survive JSON escaping. Header values are escaped
 * for double-quote and backslash, which covers every byte allowed in
 * HTTP/1.1 RFC 7230 §3.2.6.
 */
public class RedisIdempotencyStore implements IdempotencyStore, IdempotencyStoreHealth {

    /**
     * Atomic save: verify the lock token, write the record with TTL, delete
     * the lock. {@code KEYS[1]}=lock key, {@code KEYS[2]}=record key,
     * {@code ARGV[1]}=expected lock token, {@code ARGV[2]}=record JSON,
     * {@code ARGV[3]}=record TTL ms. Returns 1 on success, 0 when the lock
     * was held by someone else (the caller's save is rejected).
     */
    private static final String SAVE_LUA = """
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
            redis.call('DEL', KEYS[1])
            return 1
        else
            return 0
        end
        """;

    /**
     * Atomic release: delete the lock only if the caller still holds it.
     * Same lock-token-stealing protection as the save script.
     */
    private static final String RELEASE_LUA = """
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        else
            return 0
        end
        """;

    /**
     * Atomic lock extension: slide the lock key's TTL forward only if the caller
     * still holds it. {@code PEXPIRE} returns 1 when the key exists (extended), 0
     * when it is gone — so a token mismatch or an already-released lock yields 0.
     */
    private static final String EXTEND_LUA = """
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('PEXPIRE', KEYS[1], ARGV[2])
        else
            return 0
        end
        """;

    private static final RedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>(SAVE_LUA, Long.class);
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(RELEASE_LUA, Long.class);
    private static final RedisScript<Long> EXTEND_SCRIPT = new DefaultRedisScript<>(EXTEND_LUA, Long.class);

    private final StringRedisTemplate redis;
    private final String lockPrefix;
    private final String recordPrefix;

    public RedisIdempotencyStore(StringRedisTemplate redis, String keyPrefix) {
        this.redis = redis;
        String normalised = (keyPrefix == null || keyPrefix.isBlank()) ? "idempotency:" : keyPrefix;
        // Ensure separator presence so prefix + sub-namespace + key forms a
        // readable, non-ambiguous key. Tolerate trailing colon.
        if (!normalised.endsWith(":")) normalised = normalised + ":";
        this.lockPrefix = normalised + "lock:";
        this.recordPrefix = normalised + "rec:";
    }

    /**
     * Health probe: a single {@code PING}, borrowing and releasing a connection
     * from the configured pool via the template (so it never leaks one). PING
     * confirms reachability; it does not verify write-ability (a read replica or
     * an OOM {@code MISCONF} stop-writes still PONGs), which is a deliberate
     * trade-off to keep the probe side-effect-free on the health interval.
     */
    @Override
    public void verify() {
        redis.execute((RedisCallback<String>) connection -> connection.ping());
    }

    @Override
    public Optional<IdempotencyRecord> findRecord(IdempotencyKey key) {
        try {
            String json = redis.opsForValue().get(recordPrefix + key.value());
            if (json == null) return Optional.empty();
            return Optional.of(deserialise(key.value(), json));
        } catch (DataAccessException e) {
            throw new StoreException("Failed to read idempotency record for key " + key, e);
        }
    }

    @Override
    public Optional<LockToken> acquireLock(IdempotencyKey key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(
                lockPrefix + key.value(),
                token,
                ttl.toMillis(),
                TimeUnit.MILLISECONDS
            );
            return Boolean.TRUE.equals(ok) ? Optional.of(new StringLockToken(token)) : Optional.empty();
        } catch (DataAccessException e) {
            throw new StoreException("Failed to acquire idempotency lock for key " + key, e);
        }
    }

    @Override
    public void save(IdempotencyRecord record, LockToken token) {
        String tokenValue = unwrap(token);
        String json = serialise(record);
        long ttlMs = Math.max(1L, Duration.between(Instant.now(), record.expiresAt()).toMillis());
        try {
            Long result = redis.execute(
                SAVE_SCRIPT,
                List.of(lockPrefix + record.key(), recordPrefix + record.key()),
                tokenValue,
                json,
                String.valueOf(ttlMs)
            );
            if (result == null || result != 1L) {
                throw new IllegalStateException(
                    "save: lock token mismatch or lock expired for key " + record.key());
            }
        } catch (DataAccessException e) {
            throw new StoreException("Failed to save idempotency record for key " + record.key(), e);
        }
    }

    @Override
    public void releaseLock(IdempotencyKey key, LockToken token) {
        String tokenValue = unwrap(token);
        try {
            redis.execute(
                RELEASE_SCRIPT,
                List.of(lockPrefix + key.value()),
                tokenValue
            );
            // Zero rows deleted is fine — lock may have expired or been
            // released already. The post-condition (no lock held by us) is
            // satisfied either way.
        } catch (DataAccessException e) {
            throw new StoreException("Failed to release lock for key " + key, e);
        }
    }

    @Override
    public boolean supportsLockExtension() {
        return true;
    }

    @Override
    public boolean extendLock(IdempotencyKey key, LockToken token, Duration ttl) {
        String tokenValue = unwrap(token);
        long ttlMs = Math.max(1L, ttl.toMillis());
        try {
            Long result = redis.execute(
                EXTEND_SCRIPT,
                List.of(lockPrefix + key.value()),
                tokenValue,
                String.valueOf(ttlMs)
            );
            return result != null && result == 1L;
        } catch (DataAccessException e) {
            throw new StoreException("Failed to extend lock for key " + key, e);
        }
    }

    // === Serialisation ===

    private static String serialise(IdempotencyRecord r) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"k\":\"").append(escape(r.key())).append("\"");
        sb.append(",\"s\":").append(r.statusCode());
        sb.append(",\"b\":\"").append(Base64.getEncoder().encodeToString(r.body())).append("\"");
        if (r.contentType() != null) {
            sb.append(",\"ct\":\"").append(escape(r.contentType())).append("\"");
        }
        if (r.payloadHash() != null) {
            sb.append(",\"ph\":\"").append(escape(r.payloadHash())).append("\"");
        }
        sb.append(",\"ca\":\"").append(r.createdAt().toString()).append("\"");
        sb.append(",\"ea\":\"").append(r.expiresAt().toString()).append("\"");
        if (!r.headers().isEmpty()) {
            sb.append(",\"h\":{");
            boolean first = true;
            for (Map.Entry<String, List<String>> e : r.headers().entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape(e.getKey())).append("\":[");
                for (int i = 0; i < e.getValue().size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escape(e.getValue().get(i))).append("\"");
                }
                sb.append("]");
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Minimal inverse of {@link #serialise} — accepts only what serialise
     * could have produced. Hand-edited Redis values cause an
     * {@link IllegalStateException}.
     */
    static IdempotencyRecord deserialise(String key, String json) {
        IdempotencyRecord.Builder b = IdempotencyRecord.builder().key(key);
        int i = 1; // skip opening '{'
        while (i < json.length() && json.charAt(i) != '}') {
            if (json.charAt(i) != '"') {
                throw new IllegalStateException("malformed record JSON at " + i + ": " + json);
            }
            int nameStart = i + 1;
            i = findClosingQuote(json, nameStart);
            String name = json.substring(nameStart, i);
            i += 1; // past closing quote
            if (json.charAt(i) != ':') {
                throw new IllegalStateException("malformed record JSON at " + i + ": " + json);
            }
            i += 1; // past ':'

            switch (name) {
                case "k" -> { i = skipString(json, i); /* key already known from caller */ }
                case "s" -> i = parseIntField(json, i, b::statusCode);
                case "b" -> {
                    int[] end = new int[1];
                    String body = parseString(json, i, end);
                    b.body(Base64.getDecoder().decode(body.getBytes(StandardCharsets.US_ASCII)));
                    i = end[0];
                }
                case "ct" -> {
                    int[] end = new int[1];
                    b.contentType(parseString(json, i, end));
                    i = end[0];
                }
                case "ph" -> {
                    int[] end = new int[1];
                    b.payloadHash(parseString(json, i, end));
                    i = end[0];
                }
                case "ca" -> {
                    int[] end = new int[1];
                    b.createdAt(Instant.parse(parseString(json, i, end)));
                    i = end[0];
                }
                case "ea" -> {
                    int[] end = new int[1];
                    b.expiresAt(Instant.parse(parseString(json, i, end)));
                    i = end[0];
                }
                case "h" -> {
                    i = parseHeaders(json, i, b);
                }
                default -> throw new IllegalStateException("unknown field '" + name + "' in record JSON");
            }
            if (i < json.length() && json.charAt(i) == ',') i++;
        }
        return b.build();
    }

    private static int parseHeaders(String s, int i, IdempotencyRecord.Builder b) {
        if (s.charAt(i) != '{') throw new IllegalStateException("expected '{' for headers at " + i);
        i++;
        Map<String, List<String>> headers = new LinkedHashMap<>();
        while (s.charAt(i) != '}') {
            if (s.charAt(i) != '"') throw new IllegalStateException("expected header name at " + i);
            int nameStart = i + 1;
            i = findClosingQuote(s, nameStart);
            String name = unescape(s.substring(nameStart, i));
            i += 1; // past closing quote
            if (s.charAt(i) != ':' || s.charAt(i + 1) != '[') {
                throw new IllegalStateException("expected ':[' after header name at " + i);
            }
            i += 2;
            List<String> values = new java.util.ArrayList<>();
            while (s.charAt(i) != ']') {
                if (s.charAt(i) == ',') i++;
                int[] end = new int[1];
                values.add(parseString(s, i, end));
                i = end[0];
            }
            headers.put(name, values);
            i += 1; // past ']'
            if (i < s.length() && s.charAt(i) == ',') i++;
        }
        i += 1; // past '}'
        b.headers(headers);
        return i;
    }

    private static int parseIntField(String s, int i, java.util.function.IntConsumer setter) {
        int start = i;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '-')) i++;
        setter.accept(Integer.parseInt(s.substring(start, i)));
        return i;
    }

    private static String parseString(String s, int i, int[] endOut) {
        if (s.charAt(i) != '"') throw new IllegalStateException("expected '\"' at " + i);
        int start = i + 1;
        int end = findClosingQuote(s, start);
        endOut[0] = end + 1;
        return unescape(s.substring(start, end));
    }

    private static int skipString(String s, int i) {
        if (s.charAt(i) != '"') throw new IllegalStateException("expected '\"' at " + i);
        int end = findClosingQuote(s, i + 1);
        return end + 1;
    }

    private static int findClosingQuote(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '\\') { i++; continue; }
            if (s.charAt(i) == '"') return i;
        }
        throw new IllegalStateException("unterminated string in record JSON");
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                sb.append(s.charAt(i + 1));
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unwrap(LockToken token) {
        if (!(token instanceof StringLockToken s)) {
            throw new IllegalArgumentException("Unexpected lock token type: " + token.getClass());
        }
        return s.value;
    }

    private record StringLockToken(String value) implements LockToken {}
}
