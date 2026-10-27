package com.motion.backend.auth;

/**
 * HTTP-facing application error carrying the exact Go status and message.
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
