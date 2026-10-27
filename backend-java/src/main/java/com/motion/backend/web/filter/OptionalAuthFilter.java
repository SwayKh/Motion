package com.motion.backend.web.filter;

import com.motion.backend.auth.AuthService;
import com.motion.backend.auth.RequestAuth;
import jakarta.servlet.http.HttpServletRequest;

/**
 * OptionalAuth runs before CSRF, so a valid session is touched even when a later CSRF/auth check
 * rejects the request. Invalid or disabled sessions simply leave the request anonymous.
 */
public final class OptionalAuthFilter {

    private final AuthService authService;

    public OptionalAuthFilter(AuthService authService) {
        this.authService = authService;
    }

    public void apply(HttpServletRequest request) {
        String token = CookieWriter.find(request, CookieWriter.SESSION_COOKIE);
        if (token == null || token.isBlank()) {
            return;
        }
        authService.authenticate(token).ifPresent(authenticated ->
                request.setAttribute(RequestAuth.ATTRIBUTE, authenticated));
    }
}
