package com.motion.backend.web.filter;

import com.motion.backend.auth.AuthCrypto;
import com.motion.backend.config.MotionProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Double-submit CSRF filter matching the cookie/header rules exactly. */
public final class CsrfFilter {

    private static final String HEADER = "X-CSRF-Token";
    private static final String FORM_FIELD = "_csrf";

    private final MotionProperties properties;

    public CsrfFilter(MotionProperties properties) {
        this.properties = properties;
    }

    /** Returns false when the request was rejected and must not continue. */
    public boolean allow(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            if (CookieWriter.find(request, CookieWriter.CSRF_COOKIE) == null) {
                CookieWriter.writeCsrfCookie(response, AuthCrypto.generateToken(), properties);
            }
            return true;
        }

        // Go only enforces CSRF when any motion_session cookie is present, regardless of validity.
        if (CookieWriter.find(request, CookieWriter.SESSION_COOKIE) == null) {
            return true;
        }

        String cookie = CookieWriter.find(request, CookieWriter.CSRF_COOKIE);
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            header = request.getParameter(FORM_FIELD);
        }
        if (cookie == null || cookie.isEmpty() || header == null || !header.equals(cookie)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"invalid csrf token\"}\n");
            return false;
        }
        return true;
    }
}
