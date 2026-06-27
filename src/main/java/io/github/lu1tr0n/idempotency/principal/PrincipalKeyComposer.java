package io.github.lu1tr0n.idempotency.principal;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.exception.IdempotencyKeyTooLongException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Folds the authenticated principal into the idempotency key so that the same
 * raw key used by two different principals maps to two distinct stored keys —
 * the IETF draft §5 data-leak mitigation.
 *
 * <p>The principal is hashed (SHA-256, truncated to 128 bits / 32 lowercase hex
 * characters) and the key is namespaced: {@code p:<token>:<rawKey>} for an
 * authenticated request, {@code a:<rawKey>} for an anonymous one. Hashing keeps
 * the raw principal (an email, DN, username) out of database keys and log lines.
 *
 * <h2>Why both branches are namespaced</h2>
 *
 * <p>The {@code :} separator and hex characters are all legal in the raw-key
 * grammar, so an anonymous caller could otherwise submit the literal string
 * {@code <token-of-victim>:<key>} as their "bare" key and collide with an
 * authenticated victim's scoped record — replaying that victim's response (the
 * exact IETF §5 leak this guards against). Disjoint {@code p:} / {@code a:}
 * namespaces close that: an anonymous request can only ever produce an
 * {@code a:}-prefixed key, never a {@code p:}-prefixed one, and only the
 * authenticator can place a request in the {@code p:} namespace under a given
 * token. (When binding is {@code disabled}, neither prefix is applied and the
 * key is bare — there is no isolation in that mode by definition.)
 *
 * <p>The composed value is re-validated through {@link IdempotencyKey#of(String)},
 * so an over-long raw key combined with the prefix is rejected with the normal
 * 400 path. 128 bits is ample: the principal is asserted by the authenticator,
 * not chosen by an attacker, so the only relevant event is an accidental
 * collision between two principals that <em>also</em> reuse the identical raw key.
 */
public final class PrincipalKeyComposer {

    private static final String ALGORITHM = "SHA-256";
    private static final HexFormat HEX = HexFormat.of();

    /** 16 bytes → 32 lowercase hex chars (128-bit principal token). */
    private static final int TOKEN_BYTES = 16;

    /** Namespace marker for a principal-scoped key. */
    static final String SCOPED_PREFIX = "p:";

    /** Namespace marker for an anonymous key under active binding. */
    static final String ANONYMOUS_PREFIX = "a:";

    private PrincipalKeyComposer() {
        // Static-only.
    }

    /**
     * Returns the principal-scoped key {@code p:<token>:<rawKey>}.
     *
     * @throws IdempotencyKeyTooLongException if the composed key exceeds the
     *         {@link IdempotencyKey} length budget (propagated as a 400).
     */
    public static IdempotencyKey compose(String principal, IdempotencyKey rawKey) {
        return namespaced(SCOPED_PREFIX + token(principal) + ":", rawKey);
    }

    /**
     * Returns the anonymous-namespaced key {@code a:<rawKey>} used when binding
     * is active but the request has no authenticated principal. Keeps the
     * anonymous keyspace disjoint from the scoped one so it cannot be forged
     * into a victim's scoped key.
     *
     * @throws IdempotencyKeyTooLongException if the result exceeds the budget.
     */
    public static IdempotencyKey anonymous(IdempotencyKey rawKey) {
        return namespaced(ANONYMOUS_PREFIX, rawKey);
    }

    private static IdempotencyKey namespaced(String prefix, IdempotencyKey rawKey) {
        String composite = prefix + rawKey.value();
        if (composite.length() > IdempotencyKey.MAX_LENGTH) {
            throw new IdempotencyKeyTooLongException(
                "Namespaced idempotency key exceeds " + IdempotencyKey.MAX_LENGTH
                    + " characters. With principal-binding the effective Idempotency-Key limit is "
                    + (IdempotencyKey.MAX_LENGTH - (SCOPED_PREFIX.length() + TOKEN_BYTES * 2 + 1))
                    + " characters.");
        }
        return IdempotencyKey.of(composite);
    }

    static String token(String principal) {
        try {
            byte[] digest = MessageDigest.getInstance(ALGORITHM)
                .digest(principal.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest, 0, TOKEN_BYTES);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE; an absent provider means the
            // runtime is broken well beyond idempotency.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
