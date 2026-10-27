package com.motion.backend.web.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Applies the 1 MiB MaxBytesReader limit to non-multipart JSON request bodies. The limit is
 * enforced lazily, so oversized bodies surface to controllers as an unreadable body error.
 */
public final class LimitedJsonBodyRequest extends HttpServletRequestWrapper {

    public static final long MAX_BODY_BYTES = 1L << 20;

    private final LimitedInputStream limitedStream;
    private BufferedReader reader;

    public LimitedJsonBodyRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.limitedStream = new LimitedInputStream(request.getInputStream(), MAX_BODY_BYTES);
    }

    @Override
    public ServletInputStream getInputStream() {
        return limitedStream;
    }

    @Override
    public BufferedReader getReader() {
        if (reader == null) {
            Charset charset = StandardCharsets.UTF_8;
            String encoding = getCharacterEncoding();
            if (encoding != null && !encoding.isBlank()) {
                try {
                    charset = Charset.forName(encoding);
                } catch (RuntimeException ignored) {
                    // Keep UTF-8, matching Jackson's default.
                }
            }
            reader = new BufferedReader(new InputStreamReader(limitedStream, charset));
        }
        return reader;
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long count;
        private boolean finished;

        private LimitedInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            ensureCapacity();
            int value = delegate.read();
            if (value < 0) {
                finished = true;
            } else {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            ensureCapacity();
            int allowed = (int) Math.min(length, limit - count);
            int read = delegate.read(buffer, offset, allowed);
            if (read < 0) {
                finished = true;
            } else {
                count += read;
            }
            return read;
        }

        private void ensureCapacity() throws IOException {
            if (count >= limit) {
                finished = true;
                throw new IOException("request body too large");
            }
        }

        @Override
        public boolean isFinished() {
            return finished || delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
