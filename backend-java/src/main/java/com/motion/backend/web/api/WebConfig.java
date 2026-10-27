package com.motion.backend.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.auth.AuthService;
import com.motion.backend.config.MotionProperties;
import com.motion.backend.web.interceptor.VerificationEnforcementInterceptor;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.List;

/**
 * Wire-compatible MVC configuration: the trailing-newline JSON plus verification enforcement on
 * exactly the route families that Go wraps with {@code authMw(verified(...))}.
 */
@Component
public class WebConfig implements WebMvcConfigurer {

    private final ObjectMapper objectMapper;
    private final AuthService authService;
    private final MotionProperties properties;

    public WebConfig(ObjectMapper objectMapper, AuthService authService, MotionProperties properties) {
        this.objectMapper = objectMapper;
        this.authService = authService;
        this.properties = properties;
    }

    /**
     * Mirrors the {@code http.FileServer(http.Dir("./web"))} catch-all. Controller mappings have
     * higher precedence, so API paths are never shadowed. There is deliberately no SPA fallback.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path root = properties.staticDir().toAbsolutePath().normalize();
        registry.addResourceHandler("/**")
                .addResourceLocations(root.toUri().toString())
                .setCacheControl(org.springframework.http.CacheControl.empty())
                .setUseLastModified(true);
    }

    /** Internal forward reproduces the {@code FileServer} directory index without a redirect. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new VerificationEnforcementInterceptor(authService))
                .addPathPatterns(
                        "/api/videos", "/api/videos/**",
                        "/api/upload",
                        "/api/jobs", "/api/jobs/**",
                        "/api/timeline-projects", "/api/timeline-projects/**",
                        "/api/timeline/render");
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (int index = 0; index < converters.size(); index++) {
            if (converters.get(index) instanceof MappingJackson2HttpMessageConverter) {
                converters.set(index, new NewlineJsonConverter(objectMapper));
            }
        }
    }

    private static final class NewlineJsonConverter extends MappingJackson2HttpMessageConverter {

        private NewlineJsonConverter(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        @Override
        protected void writeInternal(@Nullable Object object, @Nullable Type type, HttpOutputMessage outputMessage)
                throws IOException {
            super.writeInternal(object, type, outputMessage);
            OutputStream body = outputMessage.getBody();
            body.write('\n');
            body.flush();
        }
    }
}
