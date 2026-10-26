package com.motion.backend;

import com.motion.backend.config.DatabaseBackupCommand;
import com.motion.backend.media.MediaMigrationCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Maintenance subcommands (run without the web server): {@code encrypt-media},
 * {@code recover-media}, and {@code backup-db <file>} (consistent online SQLite snapshot
 * via {@code VACUUM INTO}). Excluding Spring Boot's generated {@code UserDetailsService}
 * avoids logging a meaningless default password; sessions are handled by the auth filters.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class MotionApplication {

    public static void main(String[] args) {
        boolean maintenance = args.length > 0
                && ("encrypt-media".equals(args[0]) || "recover-media".equals(args[0])
                    || "backup-db".equals(args[0]));
        SpringApplicationBuilder builder = new SpringApplicationBuilder(MotionApplication.class);
        if (maintenance) {
            builder.web(WebApplicationType.NONE);
        }
        ConfigurableApplicationContext context = builder.run(args);
        if (maintenance) {
            int exit = "backup-db".equals(args[0])
                    ? context.getBean(DatabaseBackupCommand.class).run(args)
                    : context.getBean(MediaMigrationCommand.class).run(args);
            System.exit(SpringApplication.exit(context, () -> exit));
        }
    }
}
