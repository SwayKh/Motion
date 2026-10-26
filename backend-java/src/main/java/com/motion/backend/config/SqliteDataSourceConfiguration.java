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
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    private static String defaultUrl(Environment environment) {
        String dataDir = environment.getProperty("MOTION_DATA_DIR", "data");
        if (!StringUtils.hasText(dataDir)) {
            dataDir = "data";
        }
        return "jdbc:sqlite:" + dataDir + "/app.db?busy_timeout=5000&journal_mode=WAL";
    }
}
