package com.motion.backend.rate;

import com.motion.backend.config.MotionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void usesDirectPeerWhenNoProxiesAreTrusted() {
        MotionProperties properties = new MotionProperties();
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10:54321");
        request.addHeader("X-Forwarded-For", "198.51.100.99");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void walksForwardedChainRightToLeftFromTrustedPeer() {
        MotionProperties properties = new MotionProperties();
        properties.getProxy().setTrustedProxies("10.0.0.1, 10.1.0.0/16");
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1:12345");
        request.addHeader("X-Forwarded-For", "198.51.100.99, 10.1.2.3");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.99");
    }

    @Test
    void ignoresSpoofedForwardedHeaderFromUntrustedPeer() {
        MotionProperties properties = new MotionProperties();
        properties.getProxy().setTrustedProxies("10.0.0.1");
        ClientIpResolver resolver = new ClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10:12345");
        request.addHeader("X-Forwarded-For", "198.51.100.99");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void parsesHostPortAndIsolatedIpLiterals() {
        assertThat(ClientIpResolver.hostOnly("[::1]:8080")).isEqualTo("::1");
        assertThat(ClientIpResolver.hostOnly("192.0.2.5:1234")).isEqualTo("192.0.2.5");
        assertThat(ClientIpResolver.hostOnly("192.0.2.5")).isEqualTo("192.0.2.5");
        assertThat(ClientIpResolver.isTrusted("10.1.2.3", List.of("10.1.0.0/16"))).isTrue();
        assertThat(ClientIpResolver.isTrusted("10.2.2.3", List.of("10.1.0.0/16"))).isFalse();
    }
}
