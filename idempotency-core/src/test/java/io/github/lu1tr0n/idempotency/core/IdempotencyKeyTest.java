package io.github.lu1tr0n.idempotency.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for {@link IdempotencyKey} validation and RFC 8941 sf-string
 * unquoting. {@code of()} is the single choke point every resolver path funnels
 * through, so its behaviour is pinned here independently of the servlet /
 * WebFlux / AOP integration tests.
 */
class IdempotencyKeyTest {

    @Test
    void bareKey_isAcceptedUnchanged() {
        // Stripe / pre-08 clients send the raw token.
        assertThat(IdempotencyKey.of("order-123").value()).isEqualTo("order-123");
    }

    @Test
    void quotedSfString_isUnwrapped() {
        // IETF draft -08+ sends an RFC 8941 sf-string.
        assertThat(IdempotencyKey.of("\"order-123\"").value()).isEqualTo("order-123");
    }

    @Test
    void quotedAndBare_normalizeToSameKey() {
        // Both transport encodings must map to one logical key so retries match.
        assertThat(IdempotencyKey.of("\"k-1\"")).isEqualTo(IdempotencyKey.of("k-1"));
    }

    @Test
    void colonIsAllowed_forTenantPrefixComposition() {
        assertThat(IdempotencyKey.of("tenant:abc_1-2").value()).isEqualTo("tenant:abc_1-2");
    }

    @Test
    void nullKey_isRejected() {
        assertThatThrownBy(() -> IdempotencyKey.of(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyKey_isRejected() {
        assertThatThrownBy(() -> IdempotencyKey.of(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyQuotedSfString_isRejected() {
        // "" unquotes to empty, which is not a valid key.
        assertThatThrownBy(() -> IdempotencyKey.of("\"\""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loneLeadingQuote_isRejectedAsIllegalChar() {
        // Not a well-formed sf-string (no closing quote) → left literal → the
        // quote then fails the character grammar.
        assertThatThrownBy(() -> IdempotencyKey.of("\"abc"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whitespaceInKey_isRejected() {
        assertThatThrownBy(() -> IdempotencyKey.of("ab cd"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void controlCharacters_areRejected() {
        // CR/LF must never survive into log lines or Redis keys.
        assertThatThrownBy(() -> IdempotencyKey.of("abc\r\ndef"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overlongKey_isRejected() {
        assertThatThrownBy(() -> IdempotencyKey.of("a".repeat(IdempotencyKey.MAX_LENGTH + 1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void grosslyOverlongInput_isRejectedBeforeAllocation() {
        // Defense-in-depth: input beyond the largest possible valid sf-string
        // is rejected up front, regardless of source.
        assertThatThrownBy(() -> IdempotencyKey.of("a".repeat(2 * IdempotencyKey.MAX_LENGTH + 3)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maxLengthKey_isAccepted() {
        String max = "a".repeat(IdempotencyKey.MAX_LENGTH);
        assertThat(IdempotencyKey.of(max).value()).isEqualTo(max);
    }
}
