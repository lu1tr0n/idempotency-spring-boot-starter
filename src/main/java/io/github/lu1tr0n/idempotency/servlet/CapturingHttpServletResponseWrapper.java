package io.github.lu1tr0n.idempotency.servlet;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

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
 * </ul>
 */
public class CapturingHttpServletResponseWrapper extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
    private ServletOutputStream cachingStream;
    private PrintWriter cachingWriter;
    private int capturedStatus = HttpServletResponse.SC_OK;

    public CapturingHttpServletResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    @Override
    public void setStatus(int sc) {
        super.setStatus(sc);
        this.capturedStatus = sc;
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
                    delegate.write(b);
                    buffer.write(b);
                }
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    delegate.write(b, off, len);
                    buffer.write(b, off, len);
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
                    delegate.write(cbuf, off, len);
                    // Encode chars to the response's character encoding so
                    // the byte buffer matches what the client actually sees.
                    byte[] bytes = new String(cbuf, off, len)
                        .getBytes(getCharacterEncoding() != null ? getCharacterEncoding() : "UTF-8");
                    buffer.write(bytes, 0, bytes.length);
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
        buffer.reset();
        capturedStatus = HttpServletResponse.SC_OK;
    }

    /** Returns the bytes the application wrote to the response. */
    public byte[] capturedBody() {
        if (cachingWriter != null) {
            cachingWriter.flush();
        }
        return buffer.toByteArray();
    }

    public int capturedStatus() {
        return capturedStatus;
    }
}
