package com.motion.backend.processing;

/** Internal engine failure with a safe, user-facing message. */
public class ProcessingException extends RuntimeException {

    public ProcessingException(String message) {
        super(message);
    }

    public ProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
