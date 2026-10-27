package com.motion.backend.media;

import java.io.Closeable;
import java.io.IOException;

/**
 * Minimal seekable byte source used by {@link MediaFileServer}.
 *
 * <p>Offsets and {@link #size()} are always expressed in plaintext bytes. Implementations may read
 * directly from a file or decrypt a chunked stream on demand.</p>
 */
public interface MediaSource extends Closeable {

    long size();

    void seek(long offset) throws IOException;

    int read(byte[] buffer, int offset, int length) throws IOException;
}
