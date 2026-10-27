package com.motion.backend.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.auth.ApiException;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/** JSON body decoding with the single generic error message. */
public final class JsonBodies {

    private JsonBodies() {
    }

    public static <T> T read(HttpServletRequest request, ObjectMapper mapper, Class<T> type) {
        try {
            T value = mapper.readValue(request.getInputStream(), type);
            if (value == null) {
                throw new ApiException(400, "invalid request body");
            }
            return value;
        } catch (IOException exception) {
            throw new ApiException(400, "invalid request body");
        }
    }
}
