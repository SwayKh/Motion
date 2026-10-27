package com.motion.backend.rate;

import com.motion.backend.config.MotionProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Rate limiters for auth and upload endpoints, keyed by resolved client IP. */
@Component
public class RateLimitRegistry {

    private final ClientIpResolver clientIpResolver;
    private final FixedWindowRateLimiter login;
    private final FixedWindowRateLimiter register;
    private final FixedWindowRateLimiter reset;
    private final FixedWindowRateLimiter verify;
    private final FixedWindowRateLimiter upload;

    public RateLimitRegistry(MotionProperties properties, ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
        this.login = new FixedWindowRateLimiter(properties.rateLimitLogin(), Duration.ofMinutes(1));
        this.register = new FixedWindowRateLimiter(properties.rateLimitRegister(), Duration.ofMinutes(1));
        this.reset = new FixedWindowRateLimiter(properties.rateLimitReset(), Duration.ofHours(1));
        this.verify = new FixedWindowRateLimiter(properties.rateLimitVerify(), Duration.ofHours(1));
        this.upload = new FixedWindowRateLimiter(properties.rateLimitUpload(), Duration.ofMinutes(1));
    }

    /** Uploads are expensive (probe + encryption); throttle per client per minute. */
    public boolean allowUpload(HttpServletRequest request) {
        return upload.allow(clientIpResolver.resolve(request));
    }

    public boolean allowLogin(HttpServletRequest request) {
        return login.allow(clientIpResolver.resolve(request));
    }

    public boolean allowRegister(HttpServletRequest request) {
        return register.allow(clientIpResolver.resolve(request));
    }

    public boolean allowPasswordResetRequest(HttpServletRequest request) {
        return reset.allow(clientIpResolver.resolve(request));
    }

    public boolean allowVerificationResend(HttpServletRequest request) {
        return verify.allow(clientIpResolver.resolve(request));
    }

    /**
     * Deliberate Java hardening: Go only rate-limits resend, but the public verify endpoint also
     * consumes verification tokens and is limited here with the same configured budget.
     */
    public boolean allowVerification(HttpServletRequest request) {
        return verify.allow(clientIpResolver.resolve(request));
    }
}
