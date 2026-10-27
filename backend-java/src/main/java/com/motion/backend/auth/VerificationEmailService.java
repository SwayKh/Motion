package com.motion.backend.auth;

import com.motion.backend.auth.AuthRecords.User;
import com.motion.backend.auth.AuthRecords.VerificationToken;
import com.motion.backend.config.MotionProperties;
import com.motion.backend.time.LegacyTimestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Issues the single-use email verification link. Like Go, a delivery failure is logged but does
 * not fail registration; only database failures are propagated.
 */
@Component
public class VerificationEmailService {

    private static final Logger log = LoggerFactory.getLogger(VerificationEmailService.class);

    private final AuthStore store;
    private final EmailSender sender;
    private final MotionProperties properties;

    public VerificationEmailService(AuthStore store, EmailSender sender, MotionProperties properties) {
        this.store = store;
        this.sender = sender;
        this.properties = properties;
    }

    public void issue(User user) {
        String token = AuthCrypto.generateToken();
        Instant now = Instant.now();
        store.replaceVerificationToken(user.id(), new VerificationToken(
                UUID.randomUUID().toString(),
                AuthCrypto.hashToken(token),
                user.id(),
                LegacyTimestamp.format(now.plus(properties.emailVerificationTtl())),
                null,
                LegacyTimestamp.format(now)));

        String link = properties.baseUrl() + "/verify?token=" + token;
        try {
            sender.send(user.email(), "Verify your Motion account",
                    "Confirm your email address by opening this link:\n" + link
                            + "\n\nIf you did not create a Motion account you can ignore this message.");
        } catch (Exception exception) {
            log.warn("verification email delivery failed for user={}: {}", user.id(), exception.getMessage());
        }
    }
}
