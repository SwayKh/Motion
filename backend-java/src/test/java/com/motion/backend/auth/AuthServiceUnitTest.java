package com.motion.backend.auth;

import com.motion.backend.auth.AuthRecords.User;
import com.motion.backend.config.MotionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Focused ordering checks that are impractical to force through the HTTP layer. */
class AuthServiceUnitTest {

    private static final String EMAIL = "user@example.com";

    private final AuthStore store = mock(AuthStore.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final VerificationEmailService verificationEmailService = mock(VerificationEmailService.class);
    private final MotionProperties properties = mock(MotionProperties.class);
    private final AuthService service =
            new AuthService(store, emailSender, verificationEmailService, properties);

    @Test
    void resetEmailIsNotSentWhenTokenPersistenceFails() throws Exception {
        when(store.findUserByNormalizedEmail(EMAIL)).thenReturn(Optional.of(user()));
        doThrow(new DataIntegrityViolationException("database unavailable"))
                .when(store).createResetToken(any());

        service.requestPasswordReset(EMAIL);

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void resetEmailIsSentOnlyAfterTokenIsPersisted() throws Exception {
        when(store.findUserByNormalizedEmail(EMAIL)).thenReturn(Optional.of(user()));

        service.requestPasswordReset(EMAIL);

        verify(store).createResetToken(any());
        verify(emailSender).send(anyString(), anyString(), anyString());
    }

    private static User user() {
        return new User(
                "user-id", EMAIL, EMAIL, "hash", "user", "active", false, null,
                "2026-11-18 00:00:00.000", "2026-11-18 00:00:00.000");
    }
}
