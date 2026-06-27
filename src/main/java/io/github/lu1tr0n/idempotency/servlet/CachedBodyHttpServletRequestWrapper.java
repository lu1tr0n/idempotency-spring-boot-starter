package io.github.lu1tr0n.idempotency.servlet;

import io.github.lu1tr0n.idempotency.exception.IdempotencyPayloadTooLargeException;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Servlet request wrapper that snapshots the request body into a byte array on
 * construction, then serves {@code getInputStream()} / {@code getReader()}
 * from that array every time they are called.
 *
 * <p>Spring's built-in {@code ContentCachingRequestWrapper} tees bytes to a
 * cache <em>as the consumer reads</em>, which is fine for read-after-chain
 * logging but does not work for our use case: we need to read the body
 * <em>before</em> the chain to compute the payload hash, and then let the
 * chain read the same body again. With Spring's wrapper, the second read
 * sees an empty stream because the underlying delegate has been consumed.
 *
 * <p>The cost of this wrapper is one extra copy of the request body in memory.
 * Acceptable for typical JSON APIs (request bodies under a few KB); not
 * appropriate for large file uploads. The {@code maxBytes} ceiling bounds the
 * snapshot so a hostile (huge / chunked / Content-Length-spoofed) body cannot
 * exhaust the heap.
 */
public class CachedBodyHttpServletRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    /**
     * Snapshot the body with no size limit — equivalent to {@code this(request, -1)}.
     */
    public CachedBodyHttpServletRequestWrapper(HttpServletRequest request) throws IOException {
        this(request, -1);
    }

    /**
     * Snapshot the body, rejecting it once it exceeds {@code maxBytes}.
     *
     * @param maxBytes the inclusive byte ceiling; {@code <= 0} means unbounded.
     * @throws IdempotencyPayloadTooLargeException if the body exceeds {@code maxBytes}
     */
    public CachedBodyHttpServletRequestWrapper(HttpServletRequest request, int maxBytes) throws IOException {
        super(request);
        // Use the request's input stream once to snapshot the body, then
        // discard the stream — every subsequent getInputStream call returns a
        // fresh wrapper around the cached array.
        if (maxBytes <= 0) {
            this.cachedBody = request.getInputStream().readAllBytes();
            return;
        }
        // Read at most maxBytes+1: the buffer can never exceed the ceiling by
        // more than one byte regardless of the declared Content-Length or
        // chunked encoding, and that one extra byte is exactly what tells us the
        // body was over the limit. This bounds actual bytes read, not a header.
        // maxBytes+1 cannot overflow: effectiveMaxBodyBytes() caps it below
        // Integer.MAX_VALUE.
        byte[] snapshot = request.getInputStream().readNBytes(maxBytes + 1);
        if (snapshot.length > maxBytes) {
            throw new IdempotencyPayloadTooLargeException(
                "Request body exceeds the configured idempotency limit of " + maxBytes + " bytes.");
        }
        this.cachedBody = snapshot;
    }

    public byte[] cachedBody() {
        return cachedBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedServletInputStream(cachedBody);
    }

    @Override
    public BufferedReader getReader() {
        Charset cs = getCharacterEncoding() != null
            ? Charset.forName(getCharacterEncoding())
            : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(getInputStream(), cs));
    }

    private static final class CachedServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        CachedServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            // No-op — the stream is fully in-memory; callers that need
            // non-blocking semantics can use the underlying byte[] directly.
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return delegate.read(b, off, len);
        }
    }
}
