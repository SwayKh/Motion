package com.motion.backend.media;

import com.motion.backend.config.MotionProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * application {@code MOTIONE1} media encryption.
 *
 * <p>The Go implementation in {@code internal/mediacrypt/mediacrypt.go} is authoritative. This class
 * deliberately reproduces its header, chunk framing, nonce derivation, AAD, and wrapped-key format
 * byte-for-byte. No key bytes are logged or returned to callers.</p>
 */
@Component
public final class MediaCrypto {

    public static final int HEADER_SIZE = 16;
    public static final int CHUNK_SIZE = 1 << 20;

    private static final byte[] MAGIC = "MOTIONE1".getBytes(StandardCharsets.US_ASCII);
    private static final int TAG_SIZE = 16;
    private static final int NONCE_SIZE = 12;
    private static final int MASTER_KEY_SIZE = 32;
    private static final int DEK_SIZE = 32;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec masterKey;
    private final boolean enabled;

    /** Spring constructor; also used directly by unit tests with mocked properties. */
    @Autowired
    public MediaCrypto(MotionProperties properties) {
        this(properties == null ? "" : properties.encryptionKey());
    }

    MediaCrypto(String encodedMasterKey) {
        String value = encodedMasterKey == null ? "" : encodedMasterKey.trim();
        if (value.isEmpty()) {
            this.masterKey = null;
            this.enabled = false;
            return;
        }
        byte[] decoded = decodeMasterKey(value);
        if (decoded.length != MASTER_KEY_SIZE) {
            throw new IllegalArgumentException(
                    "MOTION_ENCRYPTION_KEY must decode to a 32-byte key");
        }
        this.masterKey = new SecretKeySpec(decoded, "AES");
        Arrays.fill(decoded, (byte) 0);
        this.enabled = true;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Generates a standard-base64 master key, matching the {@code GenerateMasterKey}. */
    public static String generateMasterKey() {
        byte[] raw = new byte[MASTER_KEY_SIZE];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    public byte[] newDek() throws MediaEncryptionException {
        requireEnabled();
        byte[] dek = new byte[DEK_SIZE];
        random.nextBytes(dek);
        return dek;
    }

    /** Returns standard base64 of {@code nonce || ciphertext || tag}, matching the {@code Wrap}. */
    public String wrapDek(byte[] dek) throws MediaEncryptionException {
        requireEnabled();
        requireDek(dek);
        try {
            byte[] nonce = new byte[NONCE_SIZE];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_SIZE * 8, nonce));
            byte[] sealed = cipher.doFinal(dek);
            byte[] wrapped = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, wrapped, 0, nonce.length);
            System.arraycopy(sealed, 0, wrapped, nonce.length, sealed.length);
            return Base64.getEncoder().encodeToString(wrapped);
        } catch (GeneralSecurityException exception) {
            throw new MediaEncryptionException("could not wrap media key");
        }
    }

    public byte[] unwrapDek(String wrapped) throws MediaEncryptionException {
        requireEnabled();
        if (wrapped == null || wrapped.isBlank()) {
            throw new MediaEncryptionException("encrypted media key is missing");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(wrapped.trim());
        } catch (IllegalArgumentException exception) {
            throw new MediaEncryptionException("encrypted media key is invalid");
        }
        if (raw.length < NONCE_SIZE) {
            throw new MediaEncryptionException("encrypted media key is invalid");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey,
                    new GCMParameterSpec(TAG_SIZE * 8, Arrays.copyOf(raw, NONCE_SIZE)));
            byte[] dek = cipher.doFinal(raw, NONCE_SIZE, raw.length - NONCE_SIZE);
            if (dek.length != DEK_SIZE) {
                throw new MediaEncryptionException("encrypted media key is invalid");
            }
            return dek;
        } catch (GeneralSecurityException exception) {
            throw new MediaEncryptionException("encrypted media key could not be authenticated");
        }
    }

    /** Returns true only when the first eight bytes are the {@code MOTIONE1} magic. */
    public boolean hasMagic(Path path) throws IOException {
        byte[] prefix = new byte[MAGIC.length];
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
            int total = 0;
            while (total < prefix.length) {
                int read = input.read(prefix, total, prefix.length - total);
                if (read < 0) {
                    return false;
                }
                total += read;
            }
        }
        return Arrays.equals(prefix, MAGIC);
    }

    /** Streams plaintext to an encrypted destination and fsyncs it before returning. */
    public void encryptFile(Path source, Path destination, byte[] dek)
            throws IOException, MediaEncryptionException {
        requireDek(dek);
        byte[] baseNonce = new byte[MAGIC.length];
        random.nextBytes(baseNonce);
        byte[] header = new byte[HEADER_SIZE];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        System.arraycopy(baseNonce, 0, header, MAGIC.length, baseNonce.length);

        Files.deleteIfExists(destination);
        try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
             OutputStream buffered = new BufferedOutputStream(Channels.newOutputStream(channel), 64 * 1024);
             InputStream input = Files.newInputStream(source, StandardOpenOption.READ)) {
            restrictPermissions(destination);
            buffered.write(header);

            byte[] plaintext = new byte[CHUNK_SIZE];
            long counter = 0;
            while (true) {
                int count = readChunk(input, plaintext);
                if (count == 0) {
                    break;
                }
                if (counter > 0xFFFF_FFFFL) {
                    throw new MediaEncryptionException("media is too large for MOTIONE1");
                }
                byte[] nonce = chunkNonce(baseNonce, counter);
                byte[] sealed;
                try {
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"),
                            new GCMParameterSpec(TAG_SIZE * 8, nonce));
                    cipher.updateAAD(header);
                    sealed = cipher.doFinal(plaintext, 0, count);
                } catch (GeneralSecurityException exception) {
                    throw new MediaEncryptionException("could not encrypt media chunk");
                }
                byte[] length = ByteBuffer.allocate(4).putInt(count).array();
                buffered.write(length);
                buffered.write(sealed);
                counter++;
            }
            buffered.flush();
            channel.force(true);
        }
    }

    /** Opens an encrypted file using its wrapped DEK. */
    public EncryptedMediaSource openEncrypted(Path path, String wrappedKey) throws IOException {
        return new EncryptedMediaSource(path, unwrapDek(wrappedKey));
    }

    /** Opens an encrypted file directly with a DEK; used by migration verification. */
    public EncryptedMediaSource openEncrypted(Path path, byte[] dek) throws IOException {
        return new EncryptedMediaSource(path, dek);
    }

    /** Decrypts an encrypted file into a private scratch destination. */
    public void decryptFile(Path source, Path destination, byte[] dek) throws IOException {
        Files.deleteIfExists(destination);
        try (MediaSource encrypted = openEncrypted(source, dek);
             FileChannel channel = FileChannel.open(destination, StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE);
             OutputStream output = new BufferedOutputStream(Channels.newOutputStream(channel), 64 * 1024)) {
            restrictPermissions(destination);
            copy(encrypted, output, null);
            output.flush();
            channel.force(true);
        }
    }

    /** Computes a SHA-256 digest of a plaintext path without buffering its contents. */
    public String sha256(Path path) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    /** Computes a SHA-256 digest of a plaintext source without buffering its contents. */
    public String sha256(MediaSource source, long expectedLength) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[64 * 1024];
        source.seek(0);
        long remaining = expectedLength;
        while (remaining > 0) {
            int read = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new EOFException("plaintext source ended unexpectedly");
            }
            digest.update(buffer, 0, read);
            remaining -= read;
        }
        return hex(digest.digest());
    }

    /** Copies exactly {@code length} bytes, or the complete source when length is null. */
    public static void copy(MediaSource source, OutputStream output, Long length) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = length == null ? Long.MAX_VALUE : length;
        while (remaining > 0) {
            int read = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                if (length == null) {
                    break;
                }
                throw new EOFException("plaintext source ended unexpectedly");
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static byte[] chunkNonce(byte[] baseNonce, long counter) {
        byte[] nonce = new byte[NONCE_SIZE];
        System.arraycopy(baseNonce, 0, nonce, 0, baseNonce.length);
        ByteBuffer.wrap(nonce, baseNonce.length, 4).putInt((int) counter);
        return nonce;
    }

    private static int readChunk(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = input.read(buffer, total, buffer.length - total);
            if (read < 0) {
                return total;
            }
            total += read;
        }
        return total;
    }

    private static byte[] decodeMasterKey(String encoded) {
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException standardFailure) {
            // Go falls back to unpadded URL-safe base64 only when standard decoding fails.
            if (encoded.indexOf('=') >= 0) {
                throw new IllegalArgumentException(
                        "MOTION_ENCRYPTION_KEY must be valid base64", standardFailure);
            }
            try {
                return Base64.getUrlDecoder().decode(encoded);
            } catch (IllegalArgumentException urlFailure) {
                throw new IllegalArgumentException(
                        "MOTION_ENCRYPTION_KEY must be valid base64", urlFailure);
            }
        }
    }

    private void requireEnabled() throws MediaEncryptionException {
        if (!enabled) {
            throw new MediaEncryptionException("media encryption is not configured");
        }
    }

    private static void requireDek(byte[] dek) throws MediaEncryptionException {
        if (dek == null || dek.length != DEK_SIZE) {
            throw new MediaEncryptionException("media key is invalid");
        }
    }

    private static MessageDigest newDigest() throws MediaEncryptionException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new MediaEncryptionException("SHA-256 is unavailable");
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xFF));
        }
        return result.toString();
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Files.createTempFile already uses restrictive permissions on POSIX systems.
        }
    }
}
