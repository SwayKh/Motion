package com.motion.backend.media;

/** Infrastructure upload failure. The HTTP layer returns the generic 500 message. */
public class UploadInternalException extends RuntimeException {

    public UploadInternalException(String message) {
        super(message);
    }

    public UploadInternalException(String message, Throwable cause) {
        super(message, cause);
    }
}
