package com.motion.backend.web.filter;

import com.motion.backend.config.MotionProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Go-shaped CORS middleware, outermost in the chain. Returns false when the request was fully
 * handled (preflight or untrusted OPTIONS) and must not reach security headers, auth, or CSRF.
 *
 * <p>Trusted origins are compared using the normalized scheme, host, and port (when present),
 * matching the {@code scheme://url.Host} normalization. Port-specific origin behavior should be
 * verified in the target deployment/proxy environment.</p>
 */
public final class CorsFilter {

    private final Set<String> trustedOrigins = new LinkedHashSet<>();

    public CorsFilter(MotionProperties properties) {
        for (String raw : properties.trustedOrigins()) {
            if (!raw.contains("://")) {
                continue;
            }
            try {
                URI uri = URI.create(raw);
                if (uri.getScheme() != null && uri.getHost() != null) {
                    trustedOrigins.add(canonical(uri));
                }
            } catch (IllegalArgumentException ignored) {
                // Go ignores malformed configured origins during config normalization.
            }
        }
    }

    public boolean handle(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                response.setStatus(HttpServletResponse.SC_OK);
                return false;
            }
            return true;
        }

        if (trustedOrigins.contains(canonical(origin)) || trustedOrigins.contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Content-Type, X-CSRF-Token");
            response.setHeader("Access-Control-Allow-Credentials", "true");
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                response.setStatus(HttpServletResponse.SC_OK);
                return false;
            }
            return true;
        }

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }

    private static String canonical(String origin) {
        try {
            return canonical(URI.create(origin));
        } catch (IllegalArgumentException ignored) {
            // Go falls back to the raw origin when parsing fails.
            return origin;
        }
    }

    private static String canonical(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        return port == -1 ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }
}
