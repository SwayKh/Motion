package com.motion.backend.media;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Seekable, on-demand decrypting reader for the Go {@code MOTIONE1} chunked format.
 *
 * <p>Only one 1 MiB plaintext chunk is cached. Seeking to any plaintext offset reads and
 * authenticates just the corresponding chunk; no plaintext file is created.</p>
 */
final class EncryptedMediaSource implements MediaSource {

    private static final byte[] MAGIC = "MOTIONE1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int TAG_SIZE = 16;
    private static final int LENGTH_SIZE = 4;

    private final FileChannel channel;
    private final SecretKeySpec dek;
    private final byte[] header;
    private final byte[] baseNonce;
    private final long numChunks;
    private final long lastChunkLength;
    private final long plaintextSize;

    private long offset;
    private byte[] chunk;
    private long chunkStart = -1;

    EncryptedMediaSource(Path path, byte[] dek) throws IOException {
        if (dek == null || dek.length != 32) {
            throw new MediaEncryptionException("encrypted media key is invalid");
        }
        this.dek = new SecretKeySpec(dek.clone(), "AES");
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        boolean opened = false;
        try {
            long fileSize = channel.size();
            if (fileSize < MediaCrypto.HEADER_SIZE) {
                throw new MediaEncryptionException("encrypted media header is missing");
            }
            this.header = new byte[MediaCrypto.HEADER_SIZE];
            readFullyAt(channel, ByteBuffer.wrap(header), 0, "encrypted media header");
            if (!Arrays.equals(Arrays.copyOf(header, MAGIC.length), MAGIC)) {
                throw new MediaEncryptionException("file does not use the Motion encryption format");
            }
            this.baseNonce = Arrays.copyOfRange(header, MAGIC.length, MediaCrypto.HEADER_SIZE);

            long body = fileSize - MediaCrypto.HEADER_SIZE;
            long perChunk = (long) LENGTH_SIZE + MediaCrypto.CHUNK_SIZE + TAG_SIZE;
            if (body == 0) {
                this.numChunks = 0;
                this.lastChunkLength = 0;
            } else {
                long fullChunks = body / perChunk;
                long remainder = body % perChunk;
                if (remainder == 0) {
                    this.numChunks = fullChunks;
                    this.lastChunkLength = MediaCrypto.CHUNK_SIZE;
                } else {
                    if (remainder < LENGTH_SIZE + TAG_SIZE) {
                        throw new MediaEncryptionException("encrypted media frame is incomplete");
                    }
                    this.numChunks = fullChunks + 1;
                    this.lastChunkLength = remainder - LENGTH_SIZE - TAG_SIZE;
                }
                if (lastChunkLength < 0 || lastChunkLength > MediaCrypto.CHUNK_SIZE) {
                    throw new MediaEncryptionException("encrypted media frame length is invalid");
                }
            }
            this.plaintextSize = numChunks == 0
                    ? 0
                    : (numChunks - 1) * (long) MediaCrypto.CHUNK_SIZE + lastChunkLength;

            // Authenticate the first chunk before the HTTP layer commits a 200/206 response.
            if (numChunks > 0) {
                loadChunk(0);
            }
            opened = true;
        } finally {
            if (!opened) {
                channel.close();
            }
        }
    }

    @Override
    public long size() {
        return plaintextSize;
    }

    @Override
    public void seek(long offset) throws IOException {
        if (offset < 0) {
            throw new IOException("negative media position");
        }
        this.offset = offset;
        if (chunk == null || offset < chunkStart || offset >= chunkStart + chunk.length) {
            chunk = null;
            chunkStart = -1;
        }
    }

    @Override
    public int read(byte[] buffer, int bufferOffset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (offset >= plaintextSize) {
            return -1;
        }
        long index = offset / MediaCrypto.CHUNK_SIZE;
        if (chunk == null || index != chunkStart / MediaCrypto.CHUNK_SIZE) {
            loadChunk(index);
        }
        int withinChunk = (int) (offset - chunkStart);
        int count = Math.min(length, chunk.length - withinChunk);
        System.arraycopy(chunk, withinChunk, buffer, bufferOffset, count);
        offset += count;
        return count;
    }

    @Override
    public void close() throws IOException {
        chunk = null;
        channel.close();
    }

    private void loadChunk(long index) throws IOException {
        if (index < 0 || index >= numChunks || index > 0xFFFF_FFFFL) {
            throw new MediaEncryptionException("encrypted media chunk index is invalid");
        }
        long frameOffset = MediaCrypto.HEADER_SIZE + index
                * ((long) LENGTH_SIZE + MediaCrypto.CHUNK_SIZE + TAG_SIZE);

        byte[] lengthBytes = new byte[LENGTH_SIZE];
        readFullyAt(channel, ByteBuffer.wrap(lengthBytes), frameOffset, "encrypted chunk length");
        int plainLength = ByteBuffer.wrap(lengthBytes).getInt();
        if (plainLength < 0 || plainLength > MediaCrypto.CHUNK_SIZE) {
            throw new MediaEncryptionException("encrypted chunk length is invalid");
        }
        long expectedLength = index == numChunks - 1 ? lastChunkLength : MediaCrypto.CHUNK_SIZE;
        if (plainLength != expectedLength) {
            throw new MediaEncryptionException("encrypted chunk layout is invalid");
        }

        byte[] sealed = new byte[plainLength + TAG_SIZE];
        readFullyAt(channel, ByteBuffer.wrap(sealed), frameOffset + LENGTH_SIZE, "encrypted chunk");

        byte[] nonce = new byte[12];
        System.arraycopy(baseNonce, 0, nonce, 0, baseNonce.length);
        ByteBuffer.wrap(nonce, baseNonce.length, LENGTH_SIZE).putInt((int) index);

        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(TAG_SIZE * 8, nonce));
            cipher.updateAAD(header);
            plaintext = cipher.doFinal(sealed);
        } catch (Exception exception) {
            throw new MediaEncryptionException("encrypted media failed integrity verification");
        }
        if (plaintext.length != plainLength) {
            throw new MediaEncryptionException("encrypted chunk length is invalid");
        }
        this.chunk = plaintext;
        this.chunkStart = index * MediaCrypto.CHUNK_SIZE;
    }

    private static void readFullyAt(FileChannel channel, ByteBuffer target, long position, String label)
            throws IOException {
        long current = position;
        while (target.hasRemaining()) {
            int read = channel.read(target, current);
            if (read < 0) {
                throw new EOFException(label + " ended unexpectedly");
            }
            if (read == 0) {
                continue;
            }
            current += read;
        }
    }
}
