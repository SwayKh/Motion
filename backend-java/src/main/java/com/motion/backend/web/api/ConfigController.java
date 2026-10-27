package com.motion.backend.web.api;

import com.motion.backend.config.MotionProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Public, non-sensitive client configuration. */
@RestController
public class ConfigController {

    private final MotionProperties properties;

    public ConfigController(MotionProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/config")
    public Map<String, Object> config() {
        // the encoding/json sorts map keys; preserve that exact wire order.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("maxDurationSeconds", properties.maxDurationSeconds());
        payload.put("maxUploadBytes", properties.maxUploadBytes());
        payload.put("requireEmailVerification", properties.requireEmailVerification());
        return payload;
    }
}
