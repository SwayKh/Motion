package com.motion.backend.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Zero-copy, file-backed {@link MediaSource} for legacy plaintext media. */
public final class FileMediaSource implements MediaSource {

    private final FileChannel channel;
    private final long size;

    private FileMediaSource(FileChannel channel, long size) {
        this.channel = channel;
        this.size = size;
    }

    public static FileMediaSource open(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            return new FileMediaSource(channel, channel.size());
        } catch (IOException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public void seek(long offset) throws IOException {
        if (offset < 0) {
            throw new IOException("negative media position");
        }
        channel.position(offset);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        return channel.read(ByteBuffer.wrap(buffer, offset, length));
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
