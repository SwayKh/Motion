package com.motion.backend.web.filter;

import com.motion.backend.config.MotionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorsFilterTest {

    private final CorsFilter filter = filterWithOrigins(
            "https://app.example.com:8443,https://plain.example.com");

    @Test
    void trustedOriginWithMatchingPortIsAllowed() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(filter.handle(request("https://app.example.com:8443"), response)).isTrue();
        assertThat(response.getHeader("Access-Control-Allow-Origin"))
                .isEqualTo("https://app.example.com:8443");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    @Test
    void trustedHostOnADifferentPortIsRejected() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(filter.handle(request("https://app.example.com:9443"), response)).isTrue();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void configuredHostWithoutPortDoesNotMatchRequestWithPort() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(filter.handle(request("https://plain.example.com:443"), response)).isTrue();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    private static MockHttpServletRequest request(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/config");
        request.addHeader("Origin", origin);
        return request;
    }

    private static CorsFilter filterWithOrigins(String origins) {
        MotionProperties properties = new MotionProperties();
        MotionProperties.Cors cors = new MotionProperties.Cors();
        cors.setTrustedOrigins(origins);
        properties.setCors(cors);
        return new CorsFilter(properties);
    }
}
