package com.motion.backend.auth;

import com.motion.backend.config.MotionProperties;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/** SMTP sender matching the net/smtp PLAIN-auth + STARTTLS behavior. */
public final class SmtpEmailSender implements EmailSender {

    private final JavaMailSenderImpl mailSender;
    private final String from;

    public SmtpEmailSender(MotionProperties properties) {
        this.mailSender = new JavaMailSenderImpl();
        this.mailSender.setHost(properties.smtpHost());
        this.mailSender.setPort(properties.smtpPort());
        this.mailSender.setUsername(properties.smtpUser());
        this.mailSender.setPassword(properties.smtpPass());
        this.mailSender.setDefaultEncoding("UTF-8");
        this.from = properties.smtpFrom();

        Properties javaMailProperties = new Properties();
        javaMailProperties.put("mail.smtp.auth", "true");
        javaMailProperties.put("mail.smtp.starttls.enable", "true");
        javaMailProperties.put("mail.smtp.starttls.required", "false");
        javaMailProperties.put("mail.smtp.connectiontimeout", "10000");
        javaMailProperties.put("mail.smtp.timeout", "10000");
        javaMailProperties.put("mail.smtp.writetimeout", "10000");
        this.mailSender.setJavaMailProperties(javaMailProperties);
    }

    @Override
    public void send(String to, String subject, String body) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        message.setFrom(from);
        message.setRecipients(Message.RecipientType.TO, to);
        message.setSubject(subject, "UTF-8");
        message.setText(body + "\r\n", "UTF-8");
        message.saveChanges();
        mailSender.send(message);
    }
}
