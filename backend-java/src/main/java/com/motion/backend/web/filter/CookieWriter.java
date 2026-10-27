package com.motion.backend.web.filter;

import com.motion.backend.config.MotionProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Writes cookies with Go net/http's exact attribute order and attribute set. */
public final class CookieWriter {

    public static final String SESSION_COOKIE = "motion_session";
    public static final String CSRF_COOKIE = "motion_csrf";

    private CookieWriter() {
    }

    public static String find(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public static void writeSessionCookie(HttpServletResponse response, String token, MotionProperties properties) {
        long maxAge = properties.sessionTtl().getSeconds();
        response.addHeader("Set-Cookie", SESSION_COOKIE + "=" + token
                + "; Path=/; Max-Age=" + maxAge + "; HttpOnly"
                + (properties.secureCookies() ? "; Secure" : "")
                + "; SameSite=Lax");
    }

    public static void clearSessionCookie(HttpServletResponse response, MotionProperties properties) {
        // Go serializes MaxAge < 0 as Max-Age=0.
        response.addHeader("Set-Cookie", SESSION_COOKIE + "="
                + "; Path=/; Max-Age=0; HttpOnly"
                + (properties.secureCookies() ? "; Secure" : "")
                + "; SameSite=Lax");
    }

    public static void writeCsrfCookie(HttpServletResponse response, String token, MotionProperties properties) {
        response.addHeader("Set-Cookie", CSRF_COOKIE + "=" + token
                + "; Path=/"
                + (properties.secureCookies() ? "; Secure" : "")
                + "; SameSite=Lax");
    }
}
