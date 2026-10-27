package com.motion.backend.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Single ordered filter reproducing the middleware chain:
 * CORS → SecurityHeaders → OptionalAuth → CSRF → router/controllers.
 */
public final class AppMiddlewareFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AppMiddlewareFilter.class);

    private final CorsFilter corsFilter;
    private final SecurityHeadersFilter securityHeadersFilter;
    private final OptionalAuthFilter optionalAuthFilter;
    private final CsrfFilter csrfFilter;

    public AppMiddlewareFilter(CorsFilter corsFilter, SecurityHeadersFilter securityHeadersFilter,
                              OptionalAuthFilter optionalAuthFilter, CsrfFilter csrfFilter) {
        this.corsFilter = corsFilter;
        this.securityHeadersFilter = securityHeadersFilter;
        this.optionalAuthFilter = optionalAuthFilter;
        this.csrfFilter = csrfFilter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!corsFilter.handle(request, response)) {
            return;
        }
        securityHeadersFilter.apply(response);

        // the OptionalAuthMiddleware ignores session/user lookup errors and continues anonymously.
        try {
            optionalAuthFilter.apply(request);
        } catch (Exception exception) {
            log.warn("optional authentication failed; continuing anonymously", exception);
        }

        try {
            if (!csrfFilter.allow(request, response)) {
                return;
            }
            filterChain.doFilter(limitJsonBody(request), response);
        } catch (Exception exception) {
            if (response.isCommitted()) {
                log.error("unhandled filter-chain failure after response was committed", exception);
                return;
            }
            log.error("unhandled filter-chain failure", exception);
            writeInternalError(response);
        }
    }

    private static HttpServletRequest limitJsonBody(HttpServletRequest request) throws IOException {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return request;
        }
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")) {
            return request;
        }
        return new LimitedJsonBodyRequest(request);
    }

    private static void writeInternalError(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"internal server error\"}\n");
    }
}
