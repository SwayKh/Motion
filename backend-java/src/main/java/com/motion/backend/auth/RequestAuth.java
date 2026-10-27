package com.motion.backend.auth;

import jakarta.servlet.http.HttpServletRequest;

/** Request-scoped auth context populated by the application OptionalAuth/Auth filters. */
public final class RequestAuth {

    public static final String ATTRIBUTE = "motion.authenticated";

    private RequestAuth() {
    }

    public static AuthRecords.Authenticated get(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof AuthRecords.Authenticated authenticated ? authenticated : null;
    }

    public static AuthRecords.Authenticated require(HttpServletRequest request) {
        AuthRecords.Authenticated authenticated = get(request);
        if (authenticated == null) {
            throw new ApiException(401, "unauthorized");
        }
        return authenticated;
    }
}
