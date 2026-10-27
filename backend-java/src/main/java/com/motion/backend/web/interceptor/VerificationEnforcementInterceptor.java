package com.motion.backend.web.interceptor;

import com.motion.backend.auth.AuthService;
import com.motion.backend.auth.RequestAuth;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Equivalent of the {@code RequireVerified} wrapper for the protected video/job/editor families.
 *
 * <p>It runs after OptionalAuth has populated the request context, so a missing session produces
 * the same 401 as the AuthMiddleware and an unverified account produces the same 403. Auth
 * endpoints that Go leaves outside {@code RequireVerified} are intentionally not matched.</p>
 */
public final class VerificationEnforcementInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    public VerificationEnforcementInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        authService.requireVerified(RequestAuth.get(request));
        return true;
    }
}
