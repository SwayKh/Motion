package com.motion.backend.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Local-development sender matching the DevOutbox: one-time links are appended to a
 * mode-0600 file under the data directory. This must never be enabled in production.
 */
public final class DevOutboxEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(DevOutboxEmailSender.class);
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ISO_INSTANT;

    private final Path path;
    private final Object lock = new Object();

    public DevOutboxEmailSender(Path path) {
        this.path = path;
    }

    @Override
    public void send(String to, String subject, String body) throws IOException {
        String entry = "=== " + RFC3339.format(Instant.now().atOffset(ZoneOffset.UTC)) + " ===\n"
                + "To: " + to + "\n"
                + "Subject: " + subject + "\n\n"
                + body + "\n\n";
        log.info("dev email queued to={} subject=\"{}\"", to, subject);

        if (path == null) {
            return;
        }
        synchronized (lock) {
            Path directory = path.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Files.writeString(path, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        }
    }
}
