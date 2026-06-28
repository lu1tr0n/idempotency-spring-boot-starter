package io.github.lu1tr0n.idempotency.autoconfigure;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code response-ttl-header} ceiling resolution: defaults to the global
 * {@code ttl}, honours an explicit {@code max}, and is overflow-guarded.
 */
class ResponseTtlMaxSecondsTest {

    @Test
    void defaultsToGlobalTtl() {
        IdempotencyProperties props = new IdempotencyProperties();
        props.setTtl(Duration.ofHours(24));
        // max unset → falls back to the global ttl.
        assertThat(props.effectiveResponseTtlMaxSeconds()).isEqualTo(Duration.ofHours(24).getSeconds());
    }

    @Test
    void honoursExplicitMax() {
        IdempotencyProperties props = new IdempotencyProperties();
        props.setTtl(Duration.ofHours(24));
        props.getResponseTtlHeader().setMax(Duration.ofMinutes(30));
        assertThat(props.effectiveResponseTtlMaxSeconds()).isEqualTo(1800);
    }

    @Test
    void absurdMaxIsClampedToKeepInstantArithmeticSafe() {
        IdempotencyProperties props = new IdempotencyProperties();
        props.getResponseTtlHeader().setMax(Duration.ofDays(1_000_000));
        long capped = props.effectiveResponseTtlMaxSeconds();
        // ~100 years ceiling.
        assertThat(capped).isEqualTo(100L * 365L * 24L * 60L * 60L);
    }
}
