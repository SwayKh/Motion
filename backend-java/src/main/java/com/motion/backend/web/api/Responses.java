package com.motion.backend.web.api;

import com.motion.backend.auth.ApiException;
import org.springframework.http.ResponseEntity;

/** Small helpers producing Go-shaped JSON responses. */
public final class Responses {

    private Responses() {
    }

    public static ResponseEntity<Object> ok(Object body) {
        return ResponseEntity.ok(body);
    }

    public static ResponseEntity<Object> status(int status, Object body) {
        return ResponseEntity.status(status).body(body);
    }

    public static ResponseEntity<Object> error(ApiException exception) {
        return status(exception.status(), new ApiError(exception.getMessage()));
    }
}
