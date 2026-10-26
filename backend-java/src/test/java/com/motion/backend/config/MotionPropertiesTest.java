package com.motion.backend.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MotionPropertiesTest {

    @Test
    void goDurationStringsAreParsed() {
        MotionProperties properties = new MotionProperties();
        properties.getSession().setTtl("1h30m");
        properties.getSession().setIdleTtl("15m");

        assertThat(properties.sessionTtl()).isEqualTo(Duration.ofMinutes(90));
        assertThat(properties.sessionIdleTtl()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void invalidValuesFallBackToGoDefaults() {
        MotionProperties properties = new MotionProperties();
        properties.getSession().setTtl("nonsense");
        properties.getUpload().setMaxBytes("not-a-number");
        properties.getCors().setTrustedOrigins(" https://one.example , ,http://two.example ");

        assertThat(properties.sessionTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.maxUploadBytes()).isEqualTo(157_286_400L);
        assertThat(properties.trustedOrigins()).containsExactly("https://one.example", "http://two.example");
    }

    @Test
    void secureCookiesAutoEnableOnlyInProduction() {
        MotionProperties development = new MotionProperties();
        MotionProperties production = new MotionProperties();
        production.setEnv("production");

        assertThat(development.secureCookies()).isFalse();
        assertThat(production.secureCookies()).isTrue();

        production.getSession().setSecureCookies("false");
        assertThat(production.secureCookies()).isFalse();
    }
}
