package com.motion.backend.media;

import org.springframework.stereotype.Component;

import java.util.Arrays;

/** Command-line entry point for media migration; the web server is not started for these commands. */
@Component
public class MediaMigrationCommand {

    private final MediaMigrationService migration;

    public MediaMigrationCommand(MediaMigrationService migration) {
        this.migration = migration;
    }

    public boolean isMigrationCommand(String[] args) {
        return args.length > 0
                && ("encrypt-media".equals(args[0]) || "recover-media".equals(args[0]));
    }

    public int run(String[] args) {
        if (args.length == 0) {
            return 0;
        }
        switch (args[0]) {
            case "encrypt-media" -> {
                if (Arrays.asList(args).contains("--dry-run")) {
                    MediaMigrationService.MigrationPlan plan = migration.dryRun();
                    System.out.printf("media migration dry run: %d candidates, %d already encrypted, "
                                    + "%d missing, %d active%n",
                            plan.candidates(), plan.alreadyEncrypted(), plan.missing(), plan.active());
                    return 0;
                }
                MediaMigrationService.MigrationResult result = migration.encrypt();
                System.out.printf("media migration complete: %d encrypted, %d skipped, %d failed%n",
                        result.encrypted(), result.skipped(), result.failed());
                return result.failed() == 0 ? 0 : 1;
            }
            case "recover-media" -> {
                MediaMigrationService.RecoveryResult result = migration.recover();
                System.out.printf("media recovery complete: %d completed, %d discarded, "
                                + "%d unrecognized, %d plaintext temps removed%n",
                        result.completed(), result.discarded(), result.leftUnrecognized(),
                        result.plaintextTempsRemoved());
                return 0;
            }
            default -> {
                return 0;
            }
        }
    }
}
