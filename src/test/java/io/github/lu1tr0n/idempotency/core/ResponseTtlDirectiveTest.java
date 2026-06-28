package io.github.lu1tr0n.idempotency.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the per-response TTL directive parser — the single
 * choke point shared by the servlet and reactive filters.
 */
class ResponseTtlDirectiveTest {

    private static final long MAX = 3600; // 1h ceiling for these cases

    @Test
    void validValue_isHonoured() {
        assertThat(ResponseTtlDirective.resolveSeconds(List.of("60"), MAX)).isEqualTo(60);
    }

    @Test
    void overMax_clampsDown() {
        assertThat(ResponseTtlDirective.resolveSeconds(List.of("99999"), MAX)).isEqualTo(MAX);
    }

    @Test
    void exactlyMax_isHonoured() {
        assertThat(ResponseTtlDirective.resolveSeconds(List.of("3600"), MAX)).isEqualTo(MAX);
    }

    @Test
    void zero_isIgnored() {
        assertThat(ResponseTtlDirective.resolveSeconds(List.of("0"), MAX)).isEqualTo(-1);
    }

    @Test
    void negative_isIgnored() {
        // Leading '-' is not a digit, so this is malformed → ignored.
        assertThat(ResponseTtlDirective.resolveSeconds(List.of("-5"), MAX)).isEqualTo(-1);
    }

    @Test
    void signedPositive_isIgnored() {
        assertThat(ResponseTtlDirective.resolveSeconds(List.of("+5"), MAX)).isEqualTo(-1);
    }

    @Test
    void nonNumeric_isIgnored() {
        assertThat(ResponseTtlDirective.resolveSeconds(List.of("abc"), MAX)).isEqualTo(-1);
    }

    @Test
    void surroundingWhitespace_isTrimmed() {
        assertThat(ResponseTtlDirective.resolveSeconds(List.of("  60  "), MAX)).isEqualTo(60);
    }

    @Test
    void leadingZeros_areParsed() {
        assertThat(ResponseTtlDirective.resolveSeconds(List.of("007"), MAX)).isEqualTo(7);
    }

    @Test
    void blank_isIgnored() {
        assertThat(ResponseTtlDirective.resolveSeconds(List.of("   "), MAX)).isEqualTo(-1);
    }

    @Test
    void overflowingDigitString_isIgnored() {
        // Too long for a long → malformed, NOT "the largest possible TTL".
        assertThat(ResponseTtlDirective.resolveSeconds(List.of("99999999999999999999999"), MAX)).isEqualTo(-1);
    }

    @Test
    void multipleValues_areIgnored() {
        assertThat(ResponseTtlDirective.resolveSeconds(List.of("60", "120"), MAX)).isEqualTo(-1);
    }

    @Test
    void emptyList_isIgnored() {
        assertThat(ResponseTtlDirective.resolveSeconds(List.of(), MAX)).isEqualTo(-1);
    }

    @Test
    void nullList_isIgnored() {
        assertThat(ResponseTtlDirective.resolveSeconds(null, MAX)).isEqualTo(-1);
    }

    @Test
    void nonPositiveCeiling_isInert_usesDefault() {
        // A 0 (or sub-second-truncated) ceiling must never mint a born-expired
        // record — fall back to the global TTL instead.
        assertThat(ResponseTtlDirective.resolveSeconds(List.of("60"), 0)).isEqualTo(-1);
        assertThat(ResponseTtlDirective.resolveSeconds(List.of("60"), -5)).isEqualTo(-1);
    }

    @Test
    void nonAsciiDigits_areIgnored() {
        // Arabic-Indic "٦٠" — Character.isDigit() would accept these; the
        // documented delta-seconds grammar is ASCII only.
        assertThat(ResponseTtlDirective.resolveSeconds(List.of("٦٠"), MAX)).isEqualTo(-1);
    }
}
