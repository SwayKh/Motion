package com.motion.backend.media;

import java.io.IOException;

/** Safe, client-facing failure for encrypted media. Never includes key or plaintext material. */
public class MediaEncryptionException extends IOException {

    public MediaEncryptionException(String message) {
        super(message);
    }
}
