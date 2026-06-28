package io.github.lu1tr0n.idempotency.servlet;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct unit coverage of the response capture cap — exact boundary, the writer
 * path (post-encoding byte accounting), client-stream integrity on overflow,
 * and the re-arm / fail-loud contract.
 */
class CapturingHttpServletResponseWrapperTest {

    @Test
    void exactlyAtCap_isCaptured() throws IOException {
        MockHttpServletResponse real = new MockHttpServletResponse();
        CapturingHttpServletResponseWrapper w = new CapturingHttpServletResponseWrapper(real, 64);
        w.getOutputStream().write(new byte[64]);
        assertThat(w.isOverCap()).isFalse();
        assertThat(w.capturedBody()).hasSize(64);
    }

    @Test
    void oneByteOverCap_inOneWrite_trips() throws IOException {
        MockHttpServletResponse real = new MockHttpServletResponse();
        CapturingHttpServletResponseWrapper w = new CapturingHttpServletResponseWrapper(real, 64);
        w.getOutputStream().write(new byte[65]); // single oversized chunk
        assertThat(w.isOverCap()).isTrue();
        // Client still received the full 65 bytes — the cap only drops the snapshot.
        assertThat(real.getContentAsByteArray()).hasSize(65);
        assertThatThrownBy(w::capturedBody).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void accumulatedWritesCrossingCap_trip() throws IOException {
        MockHttpServletResponse real = new MockHttpServletResponse();
        CapturingHttpServletResponseWrapper w = new CapturingHttpServletResponseWrapper(real, 64);
        w.getOutputStream().write(new byte[40]);
        assertThat(w.isOverCap()).isFalse();
        w.getOutputStream().write(new byte[30]); // 70 > 64
        assertThat(w.isOverCap()).isTrue();
        assertThat(real.getContentAsByteArray()).hasSize(70); // client got all 70
    }

    @Test
    void writerPath_countsEncodedBytes_andTeesToClient() throws IOException {
        MockHttpServletResponse real = new MockHttpServletResponse();
        real.setCharacterEncoding("UTF-8");
        CapturingHttpServletResponseWrapper w = new CapturingHttpServletResponseWrapper(real, 64);
        w.getWriter().write("x".repeat(100)); // 100 ASCII bytes > 64
        w.getWriter().flush();
        assertThat(w.isOverCap()).isTrue();
        assertThat(real.getContentAsString()).hasSize(100); // client got the full body
    }

    @Test
    void unbounded_capturesEverything() throws IOException {
        MockHttpServletResponse real = new MockHttpServletResponse();
        CapturingHttpServletResponseWrapper w = new CapturingHttpServletResponseWrapper(real, -1);
        w.getOutputStream().write(new byte[10_000]);
        assertThat(w.isOverCap()).isFalse();
        assertThat(w.capturedBody()).hasSize(10_000);
    }

    // === Per-response TTL control header ===

    @Test
    void controlHeader_isSwallowed_notWrittenToClient_andCaptured() {
        MockHttpServletResponse real = new MockHttpServletResponse();
        CapturingHttpServletResponseWrapper w =
            new CapturingHttpServletResponseWrapper(real, -1, "Idempotency-Persist-For");
        w.setHeader("Idempotency-Persist-For", "60");
        w.setHeader("Content-Type", "application/json");
        // The control header never reaches the real response.
        assertThat(real.getHeader("Idempotency-Persist-For")).isNull();
        // Other headers pass through untouched.
        assertThat(real.getHeader("Content-Type")).isEqualTo("application/json");
        // The directive value is captured for the filter to read.
        assertThat(w.controlHeaderValues()).containsExactly("60");
    }

    @Test
    void controlHeader_matchedCaseInsensitively() {
        MockHttpServletResponse real = new MockHttpServletResponse();
        CapturingHttpServletResponseWrapper w =
            new CapturingHttpServletResponseWrapper(real, -1, "Idempotency-Persist-For");
        w.addHeader("idempotency-persist-for", "30");
        assertThat(real.getHeader("idempotency-persist-for")).isNull();
        assertThat(w.controlHeaderValues()).containsExactly("30");
    }

    @Test
    void intHeaderVariants_areSwallowed() {
        MockHttpServletResponse real = new MockHttpServletResponse();
        CapturingHttpServletResponseWrapper w =
            new CapturingHttpServletResponseWrapper(real, -1, "Idempotency-Persist-For");
        w.setIntHeader("Idempotency-Persist-For", 90);
        assertThat(real.getHeader("Idempotency-Persist-For")).isNull();
        assertThat(w.controlHeaderValues()).containsExactly("90");
    }

    @Test
    void setHeader_replaces_addHeader_appends() {
        MockHttpServletResponse real = new MockHttpServletResponse();
        CapturingHttpServletResponseWrapper w =
            new CapturingHttpServletResponseWrapper(real, -1, "Idempotency-Persist-For");
        w.setHeader("Idempotency-Persist-For", "10");
        w.addHeader("Idempotency-Persist-For", "20"); // multi-valued now
        assertThat(w.controlHeaderValues()).containsExactly("10", "20");
        w.setHeader("Idempotency-Persist-For", "30"); // set() resets
        assertThat(w.controlHeaderValues()).containsExactly("30");
    }

    @Test
    void reset_clearsCapturedDirective() {
        MockHttpServletResponse real = new MockHttpServletResponse();
        CapturingHttpServletResponseWrapper w =
            new CapturingHttpServletResponseWrapper(real, -1, "Idempotency-Persist-For");
        w.setHeader("Idempotency-Persist-For", "60");
        w.reset(); // wipes all real headers — the swallowed directive must go too
        assertThat(w.controlHeaderValues()).isEmpty();
    }

    @Test
    void dateHeaderVariant_isSwallowedWithoutCapturingValue() {
        MockHttpServletResponse real = new MockHttpServletResponse();
        CapturingHttpServletResponseWrapper w =
            new CapturingHttpServletResponseWrapper(real, -1, "Idempotency-Persist-For");
        w.setDateHeader("Idempotency-Persist-For", 1_700_000_000_000L);
        // Stripped from the wire, but a date is not a valid directive → no value.
        assertThat(real.getHeader("Idempotency-Persist-For")).isNull();
        assertThat(w.controlHeaderValues()).isEmpty();
    }

    @Test
    void featureOff_controlHeaderPassesThrough() {
        MockHttpServletResponse real = new MockHttpServletResponse();
        // null name = feature off
        CapturingHttpServletResponseWrapper w = new CapturingHttpServletResponseWrapper(real, -1, null);
        w.setHeader("Idempotency-Persist-For", "60");
        assertThat(real.getHeader("Idempotency-Persist-For")).isEqualTo("60");
        assertThat(w.controlHeaderValues()).isEmpty();
    }

    @Test
    void resetBuffer_reArmsCaptureAfterOverflow() throws IOException {
        MockHttpServletResponse real = new MockHttpServletResponse();
        CapturingHttpServletResponseWrapper w = new CapturingHttpServletResponseWrapper(real, 64);
        w.getOutputStream().write(new byte[100]);
        assertThat(w.isOverCap()).isTrue();

        w.resetBuffer();
        assertThat(w.isOverCap()).isFalse();
        w.getOutputStream().write(new byte[10]);
        assertThat(w.capturedBody()).hasSize(10);
    }
}
