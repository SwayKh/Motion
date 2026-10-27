package com.motion.backend.web.filter;

import jakarta.servlet.http.HttpServletResponse;

/** Exact header set written by the SecurityHeaders middleware. */
public final class SecurityHeadersFilter {

    private static final String CSP = "default-src 'self'; script-src 'self' 'unsafe-inline'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; media-src 'self' blob:";

    public void apply(HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-XSS-Protection", "0");
        response.setHeader("Content-Security-Policy", CSP);
    }
}
