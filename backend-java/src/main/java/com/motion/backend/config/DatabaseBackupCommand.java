package com.motion.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * {@code backup-db <file>}: writes a consistent snapshot of the SQLite database using
 * SQLite's {@code VACUUM INTO} online backup mechanism, which is safe to run while the
 * application is writing (WAL-aware, transactionally consistent).
 *
 * <p>The snapshot contains everything inside {@code app.db} (users, sessions, media and
 * job rows, timeline projects). Media files and the encryption key live outside the
 * database and must be backed up alongside it — see the backup scripts and deployment
 * documentation.</p>
 */
@Component
public class DatabaseBackupCommand {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupCommand.class);

    private final JdbcTemplate jdbc;

    public DatabaseBackupCommand(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int run(String[] args) {
        String target = Arrays.stream(args).skip(1)
                .filter(value -> !value.startsWith("--"))
                .findFirst()
                .orElse("backup.db");
        try {
            Path destination = Path.of(target).toAbsolutePath().normalize();
            Files.createDirectories(destination.getParent());
            Files.deleteIfExists(destination);
            jdbc.execute("VACUUM INTO '" + destination.toString().replace("'", "''") + "'");
            long size = Files.size(destination);
            String integrity = jdbc.queryForObject("PRAGMA integrity_check", String.class);
            log.info("database backup written to {} ({} bytes, source integrity_check={})",
                    destination, size, integrity);
            System.out.printf("backup-db: wrote %s (%d bytes, source integrity_check=%s)%n",
                    destination, size, integrity);
            return 0;
        } catch (Exception exception) {
            log.error("backup-db failed", exception);
            System.err.println("backup-db failed: " + exception.getMessage());
            return 1;
        }
    }
}
