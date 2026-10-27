package com.motion.backend.media;

import com.motion.backend.config.MotionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Byte-level and seek-level coverage for the Go-compatible MOTIONE1 media format. */
class MediaCryptoTest {

    @TempDir
    Path tempDir;

    static IntStream roundTripSizes() {
        return IntStream.of(0, 1, 100, MediaCrypto.CHUNK_SIZE - 1, MediaCrypto.CHUNK_SIZE,
                MediaCrypto.CHUNK_SIZE + 1, 2 * MediaCrypto.CHUNK_SIZE + 12345);
    }

    @ParameterizedTest
    @MethodSource("roundTripSizes")
    void roundTripsPreserveExactBytesAcrossChunkSizes(int size) throws Exception {
        MediaCrypto crypto = cryptoWithKey(MediaCrypto.generateMasterKey());
        byte[] dek = crypto.newDek();
        byte[] plaintext = randomBytes(size, size + 17L);
        Path source = tempDir.resolve("plain-" + size + ".bin");
        Path encrypted = tempDir.resolve("encrypted-" + size + ".bin");
        Path decrypted = tempDir.resolve("decrypted-" + size + ".bin");
        Files.write(source, plaintext);

        crypto.encryptFile(source, encrypted, dek);
        crypto.decryptFile(encrypted, decrypted, dek);

        assertThat(crypto.hasMagic(encrypted)).isTrue();
        assertThat(Files.readAllBytes(decrypted)).isEqualTo(plaintext);
        try (EncryptedMediaSource view = crypto.openEncrypted(encrypted, dek)) {
            assertThat(view.size()).isEqualTo(size);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            MediaCrypto.copy(view, stream, null);
            assertThat(stream.toByteArray()).isEqualTo(plaintext);
        }
    }

    @Test
    void headerHasMagicAndBigEndianChunkLengths() throws Exception {
        MediaCrypto crypto = cryptoWithKey(MediaCrypto.generateMasterKey());
        byte[] dek = crypto.newDek();
        byte[] plaintext = randomBytes(MediaCrypto.CHUNK_SIZE + 25, 404L);
        Path source = tempDir.resolve("header-source.bin");
        Path encrypted = tempDir.resolve("header.enc");
        Files.write(source, plaintext);

        crypto.encryptFile(source, encrypted, dek);

        byte[] raw = Files.readAllBytes(encrypted);
        assertThat(new String(raw, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("MOTIONE1");
        assertThat(MediaCrypto.HEADER_SIZE).isEqualTo(16);
        int frameSize = 4 + MediaCrypto.CHUNK_SIZE + 16;
        // Two chunks contribute one 4-byte length and one 16-byte tag each, plus the plaintext.
        assertThat(raw.length).isEqualTo(MediaCrypto.HEADER_SIZE + 2 * (4 + 16) + plaintext.length);
        assertThat(ByteBuffer.wrap(raw, MediaCrypto.HEADER_SIZE, 4).getInt())
                .isEqualTo(MediaCrypto.CHUNK_SIZE);
        int secondFrame = MediaCrypto.HEADER_SIZE + frameSize;
        assertThat(ByteBuffer.wrap(raw, secondFrame, 4).getInt()).isEqualTo(25);
        assertThat(HexFormat.of().formatHex(Arrays.copyOfRange(raw, 8, 16)))
                .isNotEqualTo("0000000000000000");

        // A seek into the second chunk authenticates with the derived chunk nonce and header AAD.
        try (EncryptedMediaSource view = crypto.openEncrypted(encrypted, dek)) {
            byte[] tail = new byte[25];
            view.seek(MediaCrypto.CHUNK_SIZE);
            assertThat(view.read(tail, 0, tail.length)).isEqualTo(25);
            assertThat(tail).isEqualTo(Arrays.copyOfRange(plaintext, MediaCrypto.CHUNK_SIZE, plaintext.length));
        }

        // The header is authenticated: changing a base-nonce byte breaks decryption.
        byte[] tampered = raw.clone();
        tampered[8] ^= 0x01;
        Path corruptedHeader = tempDir.resolve("corrupt-header.enc");
        Files.write(corruptedHeader, tampered);
        assertThatThrownBy(() -> crypto.openEncrypted(corruptedHeader, dek))
                .isInstanceOf(MediaEncryptionException.class)
                .hasMessageContaining("integrity verification");
    }

    @Test
    void seekableReadsHandleRangesAndChunkBoundaries() throws Exception {
        MediaCrypto crypto = cryptoWithKey(MediaCrypto.generateMasterKey());
        byte[] dek = crypto.newDek();
        int size = MediaCrypto.CHUNK_SIZE + 12345;
        byte[] plaintext = randomBytes(size, 909L);
        Path source = tempDir.resolve("seek-source.bin");
        Path encrypted = tempDir.resolve("seek.enc");
        Files.write(source, plaintext);
        crypto.encryptFile(source, encrypted, dek);

        try (EncryptedMediaSource view = crypto.openEncrypted(encrypted, dek)) {
            assertThat(view.size()).isEqualTo(size);

            byte[] beginning = new byte[32];
            view.seek(0);
            assertThat(view.read(beginning, 0, beginning.length)).isEqualTo(32);
            assertThat(beginning).isEqualTo(Arrays.copyOfRange(plaintext, 0, 32));

            byte[] acrossBoundary = new byte[64];
            view.seek(MediaCrypto.CHUNK_SIZE - 16);
            assertThat(view.read(acrossBoundary, 0, 64)).isEqualTo(16);
            assertThat(view.read(acrossBoundary, 16, 48)).isEqualTo(48);
            assertThat(acrossBoundary).isEqualTo(Arrays.copyOfRange(
                    plaintext, MediaCrypto.CHUNK_SIZE - 16, MediaCrypto.CHUNK_SIZE + 48));

            byte[] atBoundary = new byte[100];
            view.seek(MediaCrypto.CHUNK_SIZE);
            assertThat(view.read(atBoundary, 0, atBoundary.length)).isEqualTo(100);
            assertThat(atBoundary).isEqualTo(Arrays.copyOfRange(
                    plaintext, MediaCrypto.CHUNK_SIZE, MediaCrypto.CHUNK_SIZE + 100));

            int suffix = 4096;
            view.seek(size - suffix);
            ByteArrayOutputStream tail = new ByteArrayOutputStream();
            MediaCrypto.copy(view, tail, (long) suffix);
            assertThat(tail.toByteArray()).isEqualTo(Arrays.copyOfRange(plaintext, size - suffix, size));

            view.seek(size);
            assertThat(view.read(new byte[16], 0, 16)).isEqualTo(-1);
            view.seek(size + 100);
            assertThat(view.read(new byte[16], 0, 16)).isEqualTo(-1);
            assertThat(view.read(new byte[16], 0, 0)).isZero();
            assertThatThrownBy(() -> view.seek(-1)).isInstanceOf(IOException.class);
        }

        try (EncryptedMediaSource view = crypto.openEncrypted(encrypted, dek)) {
            assertThat(crypto.sha256(view, view.size())).isEqualTo(crypto.sha256(source));
        }
    }

    @Test
    void wrongMasterKeyCannotUnwrapAndErrorDoesNotLeakKeys() throws Exception {
        String firstKey = MediaCrypto.generateMasterKey();
        String secondKey = MediaCrypto.generateMasterKey();
        while (secondKey.equals(firstKey)) {
            secondKey = MediaCrypto.generateMasterKey();
        }
        MediaCrypto first = cryptoWithKey(firstKey);
        MediaCrypto second = cryptoWithKey(secondKey);
        String wrapped = first.wrapDek(first.newDek());

        MediaEncryptionException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> second.unwrapDek(wrapped), MediaEncryptionException.class);

        assertThat(failure).isNotNull().hasMessageContaining("authenticated");
        assertThat(failure.getMessage()).doesNotContain(firstKey, secondKey);
    }

    @Test
    void wrongDekAndCorruptedTagHeaderOrLengthFailClosed() throws Exception {
        MediaCrypto crypto = cryptoWithKey(MediaCrypto.generateMasterKey());
        byte[] dek = crypto.newDek();
        byte[] wrongDek = crypto.newDek();
        byte[] plaintext = "short media payload".getBytes(StandardCharsets.UTF_8);
        Path source = tempDir.resolve("auth-source.bin");
        Path encrypted = tempDir.resolve("auth.enc");
        Files.write(source, plaintext);
        crypto.encryptFile(source, encrypted, dek);

        assertThatThrownBy(() -> crypto.openEncrypted(encrypted, wrongDek))
                .isInstanceOf(MediaEncryptionException.class)
                .hasMessageContaining("integrity verification");

        byte[] raw = Files.readAllBytes(encrypted);

        byte[] badTag = raw.clone();
        badTag[badTag.length - 1] ^= 0x01;
        Path tagPath = writeRaw("bad-tag.enc", badTag);
        assertThatThrownBy(() -> crypto.openEncrypted(tagPath, dek))
                .isInstanceOf(MediaEncryptionException.class)
                .hasMessageContaining("integrity verification");

        byte[] badMagic = raw.clone();
        badMagic[0] ^= 0x01;
        Path magicPath = writeRaw("bad-magic.enc", badMagic);
        assertThatThrownBy(() -> crypto.openEncrypted(magicPath, dek))
                .isInstanceOf(MediaEncryptionException.class)
                .hasMessageContaining("does not use the Motion encryption format");

        byte[] badLength = raw.clone();
        ByteBuffer.wrap(badLength, MediaCrypto.HEADER_SIZE, 4).putInt(plaintext.length + 1);
        Path lengthPath = writeRaw("bad-length.enc", badLength);
        assertThatThrownBy(() -> crypto.openEncrypted(lengthPath, dek))
                .isInstanceOf(MediaEncryptionException.class)
                .hasMessageContaining("chunk layout is invalid");

        byte[] negativeLength = raw.clone();
        ByteBuffer.wrap(negativeLength, MediaCrypto.HEADER_SIZE, 4).putInt(-1);
        Path negativePath = writeRaw("negative-length.enc", negativeLength);
        assertThatThrownBy(() -> crypto.openEncrypted(negativePath, dek))
                .isInstanceOf(MediaEncryptionException.class)
                .hasMessageContaining("chunk length is invalid");
    }

    @Test
    void hasMagicRecognizesOnlyTheMotionHeader() throws Exception {
        MediaCrypto crypto = cryptoWithKey(MediaCrypto.generateMasterKey());
        Path plaintext = writeRaw("plain.bin", "MOTIONE0-not-a-motion-file".getBytes(StandardCharsets.UTF_8));
        Path empty = writeRaw("empty.bin", new byte[0]);
        Path shortFile = writeRaw("short.bin", new byte[] {'M', 'O'});
        Path encrypted = tempDir.resolve("magic.enc");
        crypto.encryptFile(plaintext, encrypted, crypto.newDek());

        assertThat(crypto.hasMagic(encrypted)).isTrue();
        assertThat(crypto.hasMagic(plaintext)).isFalse();
        assertThat(crypto.hasMagic(empty)).isFalse();
        assertThat(crypto.hasMagic(shortFile)).isFalse();
    }

    @Test
    void baseNoncesAndPerChunkCiphertextsAreUnique() throws Exception {
        MediaCrypto crypto = cryptoWithKey(MediaCrypto.generateMasterKey());
        byte[] dek = crypto.newDek();
        Path source = writeRaw("same-source.bin", "identical plaintext".getBytes(StandardCharsets.UTF_8));

        Set<String> baseNonces = new HashSet<>();
        for (int index = 0; index < 12; index++) {
            Path encrypted = tempDir.resolve("same-" + index + ".enc");
            crypto.encryptFile(source, encrypted, dek);
            byte[] raw = Files.readAllBytes(encrypted);
            baseNonces.add(HexFormat.of().formatHex(Arrays.copyOfRange(raw, 8, 16)));
        }
        assertThat(baseNonces).hasSize(12);

        byte[] repeatedPlaintext = new byte[3 * MediaCrypto.CHUNK_SIZE];
        Arrays.fill(repeatedPlaintext, (byte) 0x5A);
        Path repeatedSource = writeRaw("repeated-source.bin", repeatedPlaintext);
        Path repeatedEncrypted = tempDir.resolve("repeated.enc");
        crypto.encryptFile(repeatedSource, repeatedEncrypted, dek);

        byte[] raw = Files.readAllBytes(repeatedEncrypted);
        int frameSize = 4 + MediaCrypto.CHUNK_SIZE + 16;
        int ciphertextSize = MediaCrypto.CHUNK_SIZE + 16;
        assertThat(raw.length).isEqualTo(MediaCrypto.HEADER_SIZE + 3 * frameSize);
        int first = MediaCrypto.HEADER_SIZE + 4;
        int second = first + frameSize;
        int third = second + frameSize;
        assertThat(Arrays.equals(raw, first, first + ciphertextSize, raw, second, second + ciphertextSize))
                .isFalse();
        assertThat(Arrays.equals(raw, second, second + ciphertextSize, raw, third, third + ciphertextSize))
                .isFalse();
    }

    @Test
    void masterKeyDecodingAcceptsStandardAndRawUrlSafeBase64() throws Exception {
        byte[] rawKey = new byte[32];
        rawKey[0] = (byte) 0xFB;
        rawKey[1] = (byte) 0xFF;
        for (int index = 2; index < rawKey.length; index++) {
            rawKey[index] = (byte) (index * 13 + 5);
        }
        String standard = Base64.getEncoder().encodeToString(rawKey);
        String urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(rawKey);
        assertThat(urlSafe).contains("-", "_").doesNotContain("=");

        MediaCrypto standardCrypto = cryptoWithKey(standard);
        MediaCrypto urlCrypto = cryptoWithKey(urlSafe);
        assertThat(standardCrypto.enabled()).isTrue();
        assertThat(urlCrypto.enabled()).isTrue();

        byte[] dek = standardCrypto.newDek();
        assertThat(urlCrypto.unwrapDek(standardCrypto.wrapDek(dek))).isEqualTo(dek);
    }

    @Test
    void masterKeyDecodingRejectsWrongLengthsAndNonBase64() {
        assertThatThrownBy(() -> new MediaCrypto(Base64.getEncoder().encodeToString(new byte[31])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32-byte");
        assertThatThrownBy(() -> new MediaCrypto(Base64.getEncoder().encodeToString(new byte[33])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32-byte");
        assertThatThrownBy(() -> new MediaCrypto("not base64!!!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid base64");
    }

    @Test
    void wrappedDekIsStandardBase64OfNonceAndSealedKey() throws Exception {
        MediaCrypto crypto = cryptoWithKey(MediaCrypto.generateMasterKey());
        byte[] dek = crypto.newDek();

        String wrapped = crypto.wrapDek(dek);

        assertThat(wrapped).doesNotContain("=");
        byte[] raw = Base64.getDecoder().decode(wrapped);
        assertThat(raw).hasSize(12 + 32 + 16);
        assertThat(Base64.getEncoder().encodeToString(raw)).isEqualTo(wrapped);
        assertThat(crypto.unwrapDek(wrapped)).isEqualTo(dek);
        assertThat(crypto.wrapDek(dek)).isNotEqualTo(wrapped);
        assertThatThrownBy(() -> crypto.wrapDek(new byte[31]))
                .isInstanceOf(MediaEncryptionException.class);
    }

    @Test
    void decryptFileWritesOnlyToSuppliedDestinationAndScratchCanBeCleaned() throws Exception {
        MediaCrypto crypto = cryptoWithKey(MediaCrypto.generateMasterKey());
        byte[] dek = crypto.newDek();
        Path inputDir = Files.createDirectories(tempDir.resolve("crypto-input"));
        Path scratchDir = Files.createDirectories(tempDir.resolve("crypto-scratch"));
        Path source = inputDir.resolve("video.bin");
        Files.write(source, randomBytes(5000, 321L));
        Path encrypted = inputDir.resolve("video.bin.enc");
        crypto.encryptFile(source, encrypted, dek);

        List<String> inputBefore = listNames(inputDir);
        Path destination = scratchDir.resolve("private-decrypted.bin");
        crypto.decryptFile(encrypted, destination, dek);

        assertThat(listNames(inputDir)).isEqualTo(inputBefore);
        assertThat(listNames(scratchDir)).containsExactly("private-decrypted.bin");
        assertThat(Files.readAllBytes(destination)).isEqualTo(Files.readAllBytes(source));
        assertThat(Files.exists(Path.of(source + ".dec"))).isFalse();
        Files.delete(destination);
        assertThat(listNames(scratchDir)).isEmpty();
    }

    @Test
    void disabledCryptoRejectsKeyOperations() {
        MediaCrypto disabled = new MediaCrypto("");

        assertThat(disabled.enabled()).isFalse();
        assertThatThrownBy(disabled::newDek)
                .isInstanceOf(MediaEncryptionException.class)
                .hasMessageContaining("not configured");
    }

    private static MediaCrypto cryptoWithKey(String key) {
        MotionProperties properties = new MotionProperties();
        properties.getEncryption().setKey(key);
        return new MediaCrypto(properties);
    }

    private Path writeRaw(String name, byte[] bytes) throws IOException {
        return Files.write(tempDir.resolve(name), bytes);
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static List<String> listNames(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
