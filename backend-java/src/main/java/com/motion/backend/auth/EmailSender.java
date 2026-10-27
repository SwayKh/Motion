package com.motion.backend.auth;

/** Minimal email-sender interface. */
public interface EmailSender {

    void send(String to, String subject, String body) throws Exception;
}
