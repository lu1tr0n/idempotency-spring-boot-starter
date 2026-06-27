package io.github.lu1tr0n.idempotency.principal;

import io.github.lu1tr0n.idempotency.core.IdempotencyKey;
import io.github.lu1tr0n.idempotency.exception.IdempotencyKeyTooLongException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for {@link PrincipalKeyComposer} — the IETF §5 principal
 * scoping. The composed key must be deterministic, in-grammar, free of the raw
 * principal, and distinct per principal.
 */
class PrincipalKeyComposerTest {

    @Test
    void compose_isDeterministicForSamePrincipalAndKey() {
        IdempotencyKey raw = IdempotencyKey.of("order-1");
        assertThat(PrincipalKeyComposer.compose("alice", raw))
            .isEqualTo(PrincipalKeyComposer.compose("alice", raw));
    }

    @Test
    void compose_differsAcrossPrincipalsForSameKey() {
        IdempotencyKey raw = IdempotencyKey.of("order-1");
        assertThat(PrincipalKeyComposer.compose("alice", raw))
            .isNotEqualTo(PrincipalKeyComposer.compose("bob", raw));
    }

    @Test
    void compose_producesInGrammarKeyWithoutRawPrincipal() {
        // A hostile principal value (email/DN/spaces) must never leak into the
        // stored key, and the result must satisfy the IdempotencyKey grammar.
        IdempotencyKey composed = PrincipalKeyComposer.compose(
            "CN=alice, OU=ops, name with spaces, alice@example.com", IdempotencyKey.of("order-1"));
        assertThat(composed.value())
            .doesNotContain("@", " ", "=", ",")
            .endsWith(":order-1")
            .matches("[A-Za-z0-9_:-]+");
    }

    @Test
    void token_is32LowercaseHexChars() {
        String token = PrincipalKeyComposer.token("alice");
        assertThat(token).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void scopedAndAnonymousNamespaces_areDisjoint() {
        // Core of the IETF §5 fix: an anonymous key can never equal a scoped one,
        // so an anonymous caller cannot forge a victim's scoped key.
        IdempotencyKey raw = IdempotencyKey.of("order-1");
        IdempotencyKey scoped = PrincipalKeyComposer.compose("alice", raw);
        assertThat(scoped.value()).startsWith("p:");

        // Even if the attacker submits the exact scoped string as their raw key,
        // the anonymous namespace prefix keeps it out of the scoped space.
        IdempotencyKey forgedAsAnonymous = PrincipalKeyComposer.anonymous(
            IdempotencyKey.of(scoped.value()));
        assertThat(forgedAsAnonymous.value()).startsWith("a:").isNotEqualTo(scoped.value());
    }

    @Test
    void anonymous_isDistinctFromBareKey() {
        IdempotencyKey raw = IdempotencyKey.of("order-1");
        assertThat(PrincipalKeyComposer.anonymous(raw).value()).isEqualTo("a:order-1");
    }

    @Test
    void compose_throwsWhenScopedKeyExceedsBudget() {
        // 32 hex + ':' = 33 chars of prefix; a 230-char raw key overflows 255.
        IdempotencyKey longKey = IdempotencyKey.of("a".repeat(IdempotencyKey.MAX_LENGTH - 10));
        assertThatThrownBy(() -> PrincipalKeyComposer.compose("alice", longKey))
            .isInstanceOf(IdempotencyKeyTooLongException.class);
    }
}
