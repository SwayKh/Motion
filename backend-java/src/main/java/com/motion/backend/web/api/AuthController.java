package com.motion.backend.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.auth.ApiException;
import com.motion.backend.auth.AuthCrypto;
import com.motion.backend.auth.AuthRecords.AuthResult;
import com.motion.backend.auth.AuthRecords.Authenticated;
import com.motion.backend.auth.AuthRecords.User;
import com.motion.backend.auth.AuthService;
import com.motion.backend.auth.RequestAuth;
import com.motion.backend.config.MotionProperties;
import com.motion.backend.rate.RateLimitRegistry;
import com.motion.backend.web.filter.CookieWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Exact Go auth/config route surface; no /api aliases are added. */
@RestController
public class AuthController {

    private final AuthService authService;
    private final MotionProperties properties;
    private final RateLimitRegistry rateLimits;
    private final ObjectMapper objectMapper;

    public AuthController(AuthService authService, MotionProperties properties,
                          RateLimitRegistry rateLimits, ObjectMapper objectMapper) {
        this.authService = authService;
        this.properties = properties;
        this.rateLimits = rateLimits;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<Object> register(HttpServletRequest request, HttpServletResponse response) {
        try {
            if (!rateLimits.allowRegister(request)) {
                throw new ApiException(429, "too many requests");
            }
            RegisterRequest body = JsonBodies.read(request, objectMapper, RegisterRequest.class);
            AuthResult result = authService.register(body.email(), body.password(), body.passwordConfirm());
            CookieWriter.writeSessionCookie(response, result.sessionToken(), properties);
            return Responses.status(201, userPayload(result.user(), true, false));
        } catch (ApiException exception) {
            return Responses.error(exception);
        }
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<Object> login(HttpServletRequest request, HttpServletResponse response) {
        try {
            if (!rateLimits.allowLogin(request)) {
                throw new ApiException(429, "too many requests");
            }
            LoginRequest body = JsonBodies.read(request, objectMapper, LoginRequest.class);
            AuthResult result = authService.login(body.email(), body.password());
            CookieWriter.writeSessionCookie(response, result.sessionToken(), properties);
            return Responses.ok(userPayload(result.user(), false, false));
        } catch (ApiException exception) {
            return Responses.error(exception);
        }
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Object> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(CookieWriter.find(request, CookieWriter.SESSION_COOKIE));
        CookieWriter.clearSessionCookie(response, properties);
        return Responses.ok(Map.of("status", "ok"));
    }

    @GetMapping("/api/auth/csrf")
    public ResponseEntity<Object> csrf(HttpServletResponse response) {
        CookieWriter.writeCsrfCookie(response, AuthCrypto.generateToken(), properties);
        return Responses.ok(Map.of("status", "ok"));
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<Object> me(HttpServletRequest request) {
        try {
            Authenticated authenticated = RequestAuth.require(request);
            return Responses.ok(userPayload(authenticated.user(), true, true));
        } catch (ApiException exception) {
            return Responses.error(exception);
        }
    }

    @PostMapping("/api/auth/password/change")
    public ResponseEntity<Object> changePassword(HttpServletRequest request) {
        try {
            Authenticated authenticated = RequestAuth.require(request);
            ChangePasswordRequest body = JsonBodies.read(request, objectMapper, ChangePasswordRequest.class);
            authService.changePassword(authenticated, body.currentPassword(),
                    body.newPassword(), body.newPasswordConfirm());
            return Responses.ok(Map.of("status", "ok"));
        } catch (ApiException exception) {
            return Responses.error(exception);
        }
    }

    @PostMapping("/api/auth/password/reset/request")
    public ResponseEntity<Object> requestPasswordReset(HttpServletRequest request) {
        // Go returns the generic success response before decoding the body when rate-limited.
        if (!rateLimits.allowPasswordResetRequest(request)) {
            return Responses.ok(Map.of("status", "ok"));
        }
        try {
            ResetRequestRequest body = JsonBodies.read(request, objectMapper, ResetRequestRequest.class);
            authService.requestPasswordReset(body.email());
        } catch (ApiException exception) {
            return Responses.error(exception);
        }
        return Responses.ok(Map.of("status", "ok"));
    }

    @PostMapping("/api/auth/password/reset")
    public ResponseEntity<Object> resetPassword(HttpServletRequest request) {
        try {
            ResetPasswordRequest body = JsonBodies.read(request, objectMapper, ResetPasswordRequest.class);
            authService.resetPassword(body.token(), body.newPassword(), body.newPasswordConfirm());
            return Responses.ok(Map.of("status", "ok"));
        } catch (ApiException exception) {
            return Responses.error(exception);
        }
    }

    @PostMapping("/api/auth/verify")
    public ResponseEntity<Object> verifyEmail(HttpServletRequest request) {
        try {
            if (!rateLimits.allowVerification(request)) {
                throw new ApiException(429, "too many requests");
            }
            VerifyRequest body = JsonBodies.read(request, objectMapper, VerifyRequest.class);
            authService.verifyEmail(body.token());
            return Responses.ok(Map.of("status", "ok"));
        } catch (ApiException exception) {
            return Responses.error(exception);
        }
    }

    @PostMapping("/api/auth/verify/resend")
    public ResponseEntity<Object> resendVerification(HttpServletRequest request) {
        try {
            Authenticated authenticated = RequestAuth.require(request);
            if (authenticated.user().emailVerified()) {
                return Responses.ok(Map.of("status", "already_verified"));
            }
            if (!rateLimits.allowVerificationResend(request)) {
                throw new ApiException(429, "too many requests");
            }
            authService.resendVerification(authenticated.user());
            return Responses.ok(Map.of("status", "ok"));
        } catch (ApiException exception) {
            return Responses.error(exception);
        }
    }

    private static Map<String, Object> userPayload(User user, boolean includeVerification, boolean includeRole) {
        // Go sorts map keys alphabetically when encoding JSON.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("email", user.email());
        if (includeVerification) {
            payload.put("emailVerified", user.emailVerified());
        }
        payload.put("id", user.id());
        if (includeRole) {
            payload.put("role", user.role());
        }
        return Map.of("user", payload);
    }

    private record RegisterRequest(String email, String password, String passwordConfirm) {
    }

    private record LoginRequest(String email, String password) {
    }

    private record ChangePasswordRequest(String currentPassword, String newPassword, String newPasswordConfirm) {
    }

    private record ResetRequestRequest(String email) {
    }

    private record ResetPasswordRequest(String token, String newPassword, String newPasswordConfirm) {
    }

    private record VerifyRequest(String token) {
    }
}
