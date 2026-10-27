package com.motion.backend.auth;

/** Mirrors the DevNull sender: delivery is intentionally unavailable. */
public final class DevNullEmailSender implements EmailSender {

    @Override
    public void send(String to, String subject, String body) {
        throw new IllegalStateException("email delivery not configured");
    }
}
