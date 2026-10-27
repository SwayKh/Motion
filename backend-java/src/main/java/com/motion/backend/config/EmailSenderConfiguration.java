package com.motion.backend.config;

import com.motion.backend.auth.DevNullEmailSender;
import com.motion.backend.auth.DevOutboxEmailSender;
import com.motion.backend.auth.EmailSender;
import com.motion.backend.auth.SmtpEmailSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Selects the configured email sender implementation. */
@Configuration
public class EmailSenderConfiguration {

    @Bean
    EmailSender emailSender(MotionProperties properties) {
        if (properties.smtpConfigured()) {
            return new SmtpEmailSender(properties);
        }
        if (properties.devEmailOutbox()) {
            return new DevOutboxEmailSender(properties.dataDir().resolve("outbox.log"));
        }
        return new DevNullEmailSender();
    }
}
