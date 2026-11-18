package com.motion.backend.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Owns the single SQLite {@link DataSource} and normalizes an explicitly empty
 * {@code MOTION_JDBC_URL}.
 *
 * <p>Spring's {@code ${VAR:default}} syntax only applies when a property is absent, so an exported
 * {@code MOTION_JDBC_URL=} otherwise reaches Hikari as an empty URL. Binding the effective
 * {@code spring.datasource} values here, after all environment sources are final, keeps Flyway and
 * JDBC on the same application URL.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SqliteDataSourceConfiguration {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    DataSource dataSource(Environment environment) {
        DataSourceProperties properties = Binder.get(environment)
                .bind("spring.datasource", DataSourceProperties.class)
                .orElseGet(DataSourceProperties::new);

        if (!StringUtils.hasText(properties.getUrl())) {
            properties.setUrl(defaultUrl(environment));
        }
        if (!StringUtils.hasText(properties.getDriverClassName())) {
            properties.setDriverClassName("org.sqlite.JDBC");
        }
        ensureDatabaseDirectory(properties.getUrl());
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * SQLite cannot open a database file whose parent directory does not exist, and this bean
     * (unlike {@code StorageService}) runs before anything else touches the database. On a fresh
     * checkout with the default {@code data/app.db} location the directory would otherwise be
     * missing and startup would fail before the storage service could create it.
     */
    private static void ensureDatabaseDirectory(String url) {
        if (url == null || !url.startsWith("jdbc:sqlite:")) {
            return;
        }
        String path = url.substring("jdbc:sqlite:".length());
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (path.startsWith("file:")) {
            path = path.substring("file:".length());
        }
        if (path.isBlank() || path.startsWith(":memory:") || path.startsWith("resource:")) {
            return;
        }
        Path parent = Path.of(path).toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create database directory " + parent, exception);
        }
    }

    private static String defaultUrl(Environment environment) {
        String dataDir = environment.getProperty("MOTION_DATA_DIR", "data");
        if (!StringUtils.hasText(dataDir)) {
            dataDir = "data";
        }
        return "jdbc:sqlite:" + dataDir + "/app.db?busy_timeout=5000&journal_mode=WAL";
    }
}
