package com.motion.backend.security;

import com.motion.backend.auth.AuthService;
import com.motion.backend.config.MotionProperties;
import com.motion.backend.web.filter.CorsFilter;
import com.motion.backend.web.filter.CsrfFilter;
import com.motion.backend.web.filter.AppMiddlewareFilter;
import com.motion.backend.web.filter.OptionalAuthFilter;
import com.motion.backend.web.filter.SecurityHeadersFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Spring Security is used only as the servlet filter host. Authentication, authorization, CORS,
 * headers, and CSRF are implemented by the application middleware filter so the observable
 * contract stays identical to the routing.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final MotionProperties properties;
    private final AuthService authService;

    public SecurityConfig(MotionProperties properties, AuthService authService) {
        this.properties = properties;
        this.authService = authService;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AppMiddlewareFilter goMiddleware = new AppMiddlewareFilter(
                new CorsFilter(properties),
                new SecurityHeadersFilter(),
                new OptionalAuthFilter(authService),
                new CsrfFilter(properties));

        http
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .headers(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "forbidden")))
                .addFilterBefore(goMiddleware, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}\n");
    }
}
