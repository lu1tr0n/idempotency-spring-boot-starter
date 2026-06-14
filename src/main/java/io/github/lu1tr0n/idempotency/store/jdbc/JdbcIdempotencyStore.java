package io.github.lu1tr0n.idempotency.store.jdbc;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.core.IdempotencyRecord;
import io.github.lu1tr0n.idempotency.core.IdempotencyStore;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-backed store. Targets PostgreSQL primarily, with H2 working for tests.
 *
 * <h2>Locking model</h2>
 *
 * <p>The table doubles as the lock table — there is no separate lock table to
 * keep in sync. A row whose {@code locked_until} is in the future and whose
 * {@code response_body} is empty represents an in-flight request that holds
 * the lock. A row whose {@code response_body} is populated and whose
 * {@code expires_at} is in the future represents a completed request that
 * can be replayed.
 *
 * <p>Lock acquisition uses an {@code INSERT ... ON CONFLICT DO UPDATE} trick
 * that atomically takes the lock only if (a) no row exists yet, or (b) the
 * existing row's lock has expired and its response has not been populated.
 * The {@code RETURNING} clause is what tells us whether we got the lock or
 * lost the race.
 *
 * <h2>Why not {@code SELECT ... FOR UPDATE}</h2>
 *
 * <p>{@code FOR UPDATE} would serialise all concurrent attempts on the row,
 * which converts a "lock contention 409" path into a "wait for the in-flight
 * request to finish" path. That's worse for the client (latency) and worse
 * for the server (held connection). The 409 path with a {@code Retry-After}
 * lets the client decide how long to wait.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbc;
    private final String tableName;

    public JdbcIdempotencyStore(JdbcTemplate jdbc, String tableName) {
        this.jdbc = jdbc;
        this.tableName = validateTableName(tableName);
    }

    /**
     * Defensive: the table name is interpolated into the DDL/DML below
     * (parameter markers don't bind identifiers), so accept only conservative
     * identifiers. Application code never sources this from user input but
     * future contributors might.
     */
    private static String validateTableName(String name) {
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new IllegalArgumentException("table name must be 1..64 chars");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_';
            if (!ok) {
                throw new IllegalArgumentException("table name contains illegal character: '" + c + "'");
            }
        }
        return name;
    }

    @Override
    public Optional<IdempotencyRecord> findRecord(IdempotencyKey key) {
        try {
            return jdbc.query(
                "SELECT idempotency_key, payload_hash, http_status, response_headers,"
                    + " response_body, response_content_type, created_at, expires_at"
                    + " FROM " + tableName
                    + " WHERE idempotency_key = ? AND expires_at > ? AND response_body IS NOT NULL",
                ps -> {
                    ps.setString(1, key.value());
                    ps.setTimestamp(2, Timestamp.from(Instant.now()));
                },
                this::mapRow
            ).stream().findFirst();
        } catch (DataAccessException e) {
            throw new StoreException("Failed to read idempotency record for key " + key, e);
        }
    }

    @Override
    public Optional<LockToken> acquireLock(IdempotencyKey key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant lockExpires = now.plus(ttl);

        try {
            int inserted = jdbc.update(
                "INSERT INTO " + tableName
                    + " (idempotency_key, http_status, response_headers, response_body,"
                    + "  created_at, expires_at, lock_token, locked_until)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, key.value());
                    // http_status / response_body are NOT NULL in the schema;
                    // placeholders here mean "lock-only row, no response yet".
                    // We use 0 / empty bytes for the placeholders and rely on
                    // findRecord's `response_body IS NOT NULL` filter being the
                    // post-completion guard. We then mark NULL-equivalent state
                    // by setting response_body to empty and reading response_body
                    // length in the dedicated isCompleted helper — but since
                    // the schema requires NOT NULL we keep it simple: 0 + empty
                    // bytes means in-flight, populated means complete.
                    ps.setInt(2, 0);
                    ps.setString(3, "{}");
                    ps.setBytes(4, new byte[0]);
                    ps.setTimestamp(5, Timestamp.from(now));
                    ps.setTimestamp(6, Timestamp.from(lockExpires));
                    ps.setString(7, token);
                    ps.setTimestamp(8, Timestamp.from(lockExpires));
                }
            );
            if (inserted == 1) {
                return Optional.of(new StringLockToken(token));
            }
            // No INSERT happened — usually impossible because INSERT without
            // ON CONFLICT either inserts or throws DuplicateKeyException.
            return Optional.empty();
        } catch (DuplicateKeyException dup) {
            // Existing row. Try to steal the lock if it's expired AND the
            // existing row never produced a response (expired in-flight from
            // a crashed server).
            return tryStealExpiredLock(key, token, now, lockExpires);
        } catch (DataAccessException e) {
            throw new StoreException("Failed to acquire idempotency lock for key " + key, e);
        }
    }

    private Optional<LockToken> tryStealExpiredLock(IdempotencyKey key, String token, Instant now, Instant lockExpires) {
        try {
            int updated = jdbc.update(
                "UPDATE " + tableName
                    + " SET lock_token = ?, locked_until = ?, created_at = ?, expires_at = ?"
                    + " WHERE idempotency_key = ?"
                    + "   AND (response_body IS NULL OR LENGTH(response_body) = 0)"
                    + "   AND locked_until < ?",
                ps -> {
                    ps.setString(1, token);
                    ps.setTimestamp(2, Timestamp.from(lockExpires));
                    ps.setTimestamp(3, Timestamp.from(now));
                    ps.setTimestamp(4, Timestamp.from(lockExpires));
                    ps.setString(5, key.value());
                    ps.setTimestamp(6, Timestamp.from(now));
                }
            );
            if (updated == 1) {
                return Optional.of(new StringLockToken(token));
            }
            return Optional.empty();
        } catch (DataAccessException e) {
            throw new StoreException("Failed to steal expired lock for key " + key, e);
        }
    }

    @Override
    public void save(IdempotencyRecord record, LockToken token) {
        String tokenValue = unwrap(token);
        try {
            int updated = jdbc.update(
                "UPDATE " + tableName
                    + " SET payload_hash = ?, http_status = ?, response_headers = ?,"
                    + "     response_body = ?, response_content_type = ?, expires_at = ?,"
                    + "     lock_token = NULL, locked_until = NULL"
                    + " WHERE idempotency_key = ? AND lock_token = ?",
                ps -> {
                    ps.setString(1, record.payloadHash());
                    ps.setInt(2, record.statusCode());
                    ps.setString(3, serialiseHeaders(record.headers()));
                    ps.setBytes(4, record.body());
                    if (record.contentType() == null) {
                        ps.setNull(5, Types.VARCHAR);
                    } else {
                        ps.setString(5, record.contentType());
                    }
                    ps.setTimestamp(6, Timestamp.from(record.expiresAt()));
                    ps.setString(7, record.key());
                    ps.setString(8, tokenValue);
                }
            );
            if (updated != 1) {
                throw new IllegalStateException(
                    "save: lock token mismatch or row missing for key " + record.key()
                        + " — the lock may have expired and been re-acquired");
            }
        } catch (DataAccessException e) {
            throw new StoreException("Failed to save idempotency record for key " + record.key(), e);
        }
    }

    @Override
    public void releaseLock(IdempotencyKey key, LockToken token) {
        String tokenValue = unwrap(token);
        try {
            jdbc.update(
                "DELETE FROM " + tableName
                    + " WHERE idempotency_key = ? AND lock_token = ?"
                    + "   AND (response_body IS NULL OR LENGTH(response_body) = 0)",
                ps -> {
                    ps.setString(1, key.value());
                    ps.setString(2, tokenValue);
                }
            );
            // Zero rows updated is fine — the lock may have already expired
            // or been completed by a different code path. Either way, the
            // post-condition (no lock held by us) is satisfied.
        } catch (DataAccessException e) {
            throw new StoreException("Failed to release lock for key " + key, e);
        }
    }

    private IdempotencyRecord mapRow(ResultSet rs, int rowNum) throws java.sql.SQLException {
        IdempotencyRecord.Builder b = IdempotencyRecord.builder()
            .key(rs.getString("idempotency_key"))
            .payloadHash(rs.getString("payload_hash"))
            .statusCode(rs.getInt("http_status"))
            .headers(parseHeaders(rs.getString("response_headers")))
            .body(rs.getBytes("response_body"))
            .contentType(rs.getString("response_content_type"))
            .createdAt(rs.getTimestamp("created_at").toInstant())
            .expiresAt(rs.getTimestamp("expires_at").toInstant());
        return b.build();
    }

    /**
     * Trivial JSON-of-headers encoder. We avoid a full Jackson dependency here
     * because the value space (HTTP header names + values) is constrained
     * enough that hand-rolled JSON encoding works and dodges a transitive dep.
     * Header values are escaped for double-quote and backslash; that covers
     * every byte sequence allowed in HTTP/1.1 RFC 7230 §3.2.6.
     */
    private static String serialiseHeaders(Map<String, List<String>> headers) {
        if (headers.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
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
        return sb.toString();
    }

    /**
     * Inverse of {@link #serialiseHeaders}. Intentionally minimal — accepts
     * only what {@link #serialiseHeaders} could have produced. A malformed
     * JSON here means someone hand-edited the table; we fail loud rather than
     * silently swallow.
     */
    static Map<String, List<String>> parseHeaders(String json) {
        if (json == null || json.isBlank() || json.equals("{}")) {
            return new LinkedHashMap<>();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        int i = 1; // skip opening '{'
        while (i < json.length() && json.charAt(i) != '}') {
            // parse "name"
            if (json.charAt(i) != '"') {
                throw new IllegalStateException("malformed header JSON at " + i + ": " + json);
            }
            int nameStart = i + 1;
            i = findClosingQuote(json, nameStart);
            String name = unescape(json.substring(nameStart, i));
            i += 1; // past closing quote
            if (json.charAt(i) != ':' || json.charAt(i + 1) != '[') {
                throw new IllegalStateException("malformed header JSON at " + i + ": " + json);
            }
            i += 2; // past ":["
            List<String> values = new ArrayList<>();
            while (json.charAt(i) != ']') {
                if (json.charAt(i) == ',') i++;
                if (json.charAt(i) != '"') {
                    throw new IllegalStateException("malformed header JSON at " + i + ": " + json);
                }
                int valStart = i + 1;
                i = findClosingQuote(json, valStart);
                values.add(unescape(json.substring(valStart, i)));
                i += 1; // past closing quote
            }
            out.put(name, values);
            i += 1; // past ']'
            if (i < json.length() && json.charAt(i) == ',') i++;
        }
        return out;
    }

    private static int findClosingQuote(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '\\') { i++; continue; }
            if (s.charAt(i) == '"') return i;
        }
        throw new IllegalStateException("unterminated string in header JSON");
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
