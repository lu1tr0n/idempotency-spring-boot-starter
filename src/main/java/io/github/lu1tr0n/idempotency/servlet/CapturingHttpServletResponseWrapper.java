package io.github.lu1tr0n.idempotency.servlet;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Servlet response wrapper that captures every byte written to the response,
 * preserving the original status / headers / content-type so the filter can
 * persist a faithful snapshot to the {@code IdempotencyStore}.
 *
 * <p>Spring's {@code org.springframework.web.util.ContentCachingResponseWrapper}
 * does most of this already, but we keep our own copy here to avoid depending
 * on it transitively — {@code spring-web} is {@code compileOnly} in this
 * starter and we want the response wrapper available even in tests that wire
 * the filter manually without the full {@code spring-webmvc} stack.
 *
 * <p>Behaviour vs Spring's version:
 *
 * <ul>
 *   <li>Tees writes to the underlying response AND an in-memory buffer (so the
 *       client still sees the response in real time, not just after the
 *       filter completes).</li>
 *   <li>Captures the writer / stream chosen by the application — using both
 *       throws {@link IllegalStateException} per the servlet spec.</li>
 *   <li>Does NOT support reset (call {@link #reset()} → buffer is cleared).</li>
 *   <li>Bounds the captured copy at {@code maxResponseBytes}: once the response
 *       exceeds it the partial buffer is <strong>dropped</strong> (the
 *       reference is released so the memory is freed, not merely
 *       {@code reset()}) and {@link #isOverCap()} flips. Bytes keep streaming to
 *       the client unchanged; only the in-memory snapshot is abandoned, so the
 *       filter declines to cache the response.</li>
 * </ul>
 */
public class CapturingHttpServletResponseWrapper extends HttpServletResponseWrapper {

    private ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
    private ServletOutputStream cachingStream;
    private PrintWriter cachingWriter;
    private int capturedStatus = HttpServletResponse.SC_OK;

    /** Inclusive ceiling on the captured copy; {@code -1} = unbounded. */
    private final int maxResponseBytes;
    private long capturedBytes = 0;
    private boolean overCap = false;

    /**
     * Name of the per-response TTL control header to swallow, or {@code null}
     * when the feature is off. When set, values written under this name are
     * captured here and NOT forwarded to the real response, so the directive
     * never reaches the client; the filter reads them back via
     * {@link #controlHeaderValues()} to compute the record TTL.
     */
    private final String controlHeaderName;
    private final List<String> controlHeaderValues = new ArrayList<>();

    public CapturingHttpServletResponseWrapper(HttpServletResponse response) {
        this(response, -1);
    }

    public CapturingHttpServletResponseWrapper(HttpServletResponse response, int maxResponseBytes) {
        this(response, maxResponseBytes, null);
    }

    public CapturingHttpServletResponseWrapper(HttpServletResponse response, int maxResponseBytes,
                                               String controlHeaderName) {
        super(response);
        this.maxResponseBytes = maxResponseBytes;
        this.controlHeaderName = controlHeaderName;
    }

    /**
     * Append to the capture buffer unless doing so would exceed the cap. On the
     * first overflowing write the buffer reference is dropped to free the
     * already-accumulated bytes and {@link #overCap} latches — subsequent writes
     * are no-ops for the capture (the client copy is teed separately).
     */
    private void capture(byte[] b, int off, int len) {
        if (overCap) {
            return;
        }
        if (maxResponseBytes < 0) {
            buffer.write(b, off, len);
            return;
        }
        if (capturedBytes + len > maxResponseBytes) {
            overCap = true;
            buffer = null; // drop the reference so the partial snapshot is GC'd
            return;
        }
        buffer.write(b, off, len);
        capturedBytes += len;
    }

    /** Single-byte variant of {@link #capture(byte[], int, int)}. */
    private void captureByte(int b) {
        if (overCap) {
            return;
        }
        if (maxResponseBytes < 0) {
            buffer.write(b);
            return;
        }
        if (capturedBytes + 1 > maxResponseBytes) {
            overCap = true;
            buffer = null;
            return;
        }
        buffer.write(b);
        capturedBytes += 1;
    }

    @Override
    public void setStatus(int sc) {
        super.setStatus(sc);
        this.capturedStatus = sc;
    }

    // === Per-response TTL control header ===
    // When a control header name is configured, intercept the four header
    // mutators so the directive is captured here and never written to the real
    // response. set* replaces any prior captured values (HTTP set semantics);
    // add* appends. A second value makes the directive multi-valued, which the
    // parser then ignores.

    private boolean isControlHeader(String name) {
        return controlHeaderName != null && controlHeaderName.equalsIgnoreCase(name);
    }

    @Override
    public void setHeader(String name, String value) {
        if (isControlHeader(name)) {
            controlHeaderValues.clear();
            controlHeaderValues.add(value);
            return;
        }
        super.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        if (isControlHeader(name)) {
            controlHeaderValues.add(value);
            return;
        }
        super.addHeader(name, value);
    }

    @Override
    public void setIntHeader(String name, int value) {
        if (isControlHeader(name)) {
            controlHeaderValues.clear();
            controlHeaderValues.add(Integer.toString(value));
            return;
        }
        super.setIntHeader(name, value);
    }

    @Override
    public void addIntHeader(String name, int value) {
        if (isControlHeader(name)) {
            controlHeaderValues.add(Integer.toString(value));
            return;
        }
        super.addIntHeader(name, value);
    }

    @Override
    public void setDateHeader(String name, long date) {
        // A date is never a valid delta-seconds directive: strip it from the wire
        // (so the control header can't leak via a date setter) but capture no
        // value, so the directive is simply ignored.
        if (isControlHeader(name)) {
            return;
        }
        super.setDateHeader(name, date);
    }

    @Override
    public void addDateHeader(String name, long date) {
        if (isControlHeader(name)) {
            return;
        }
        super.addDateHeader(name, date);
    }

    /**
     * Values written under the configured control header, in order. Empty when
     * the feature is off or the handler set no override. The filter passes these
     * to {@code ResponseTtlDirective.resolveSeconds} — more than one value is a
     * multi-valued directive and is ignored there.
     */
    public List<String> controlHeaderValues() {
        return controlHeaderValues;
    }

    @Override
    public void sendError(int sc) throws IOException {
        this.capturedStatus = sc;
        super.sendError(sc);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        this.capturedStatus = sc;
        super.sendError(sc, msg);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (cachingStream == null) {
            ServletOutputStream delegate = super.getOutputStream();
            cachingStream = new ServletOutputStream() {
                @Override
                public boolean isReady() { return delegate.isReady(); }
                @Override
                public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
                @Override
                public void write(int b) throws IOException {
                    delegate.write(b);             // client copy first, unconditional
                    captureByte(b);
                }
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    delegate.write(b, off, len);   // client copy first, unconditional
                    capture(b, off, len);
                }
                @Override
                public void flush() throws IOException { delegate.flush(); }
                @Override
                public void close() throws IOException { delegate.close(); }
            };
        }
        return cachingStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (cachingWriter == null) {
            PrintWriter delegate = super.getWriter();
            cachingWriter = new PrintWriter(new java.io.Writer() {
                @Override
                public void write(char[] cbuf, int off, int len) throws IOException {
                    delegate.write(cbuf, off, len);   // client copy first, unconditional
                    // Encode chars to the response's character encoding so the
                    // captured bytes (and the cap accounting) match what the
                    // client actually sees on the wire.
                    byte[] bytes = new String(cbuf, off, len)
                        .getBytes(getCharacterEncoding() != null ? getCharacterEncoding() : "UTF-8");
                    capture(bytes, 0, bytes.length);
                }
                @Override public void flush() throws IOException { delegate.flush(); }
                @Override public void close() throws IOException { delegate.close(); }
            }, true);
        }
        return cachingWriter;
    }

    @Override
    public void reset() {
        super.reset();
        rearmCapture();
        capturedStatus = HttpServletResponse.SC_OK;
        // reset() clears every real header; the swallowed control header must go
        // with them, or a directive set before a reset() would still drive the
        // record TTL while every other header the handler set is gone.
        controlHeaderValues.clear();
    }

    @Override
    public void resetBuffer() {
        super.resetBuffer();
        // resetBuffer() discards the unwritten response body but keeps status /
        // headers. Mirror that on the capture side, or the snapshot would retain
        // bytes the client never received. Status is intentionally left as-is.
        rearmCapture();
    }

    /** Re-arm the capture buffer from scratch (it may have been dropped on overflow). */
    private void rearmCapture() {
        buffer = new ByteArrayOutputStream(1024);
        capturedBytes = 0;
        overCap = false;
    }

    /**
     * Returns the bytes the application wrote to the response. Callers must gate
     * on {@link #isOverCap()} first: an over-cap response has no faithful
     * snapshot, so this throws rather than hand back a misleading partial/empty
     * body that could be cached and replayed.
     *
     * @throws IllegalStateException if {@link #isOverCap()} is {@code true}
     */
    public byte[] capturedBody() {
        // Flush first — a final flush can push the capture over the cap.
        if (cachingWriter != null) {
            cachingWriter.flush();
        }
        if (overCap || buffer == null) {
            throw new IllegalStateException(
                "capturedBody() called on an over-cap response; gate on isOverCap() and do not cache it.");
        }
        return buffer.toByteArray();
    }

    /**
     * {@code true} once the response exceeded {@code maxResponseBytes} and the
     * captured copy was abandoned. Such a response must not be cached.
     */
    public boolean isOverCap() {
        return overCap;
    }

    public int capturedStatus() {
        return capturedStatus;
    }
}
