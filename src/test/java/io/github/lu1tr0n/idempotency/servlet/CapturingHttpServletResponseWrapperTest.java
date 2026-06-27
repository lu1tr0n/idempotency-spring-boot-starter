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
