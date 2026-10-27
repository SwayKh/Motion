package com.motion.backend.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static guardrails for deployment findings that cannot be exercised with Docker in this
 * environment. These read the module files directly and never touch production deployment files.
 */
class DeploymentConfigurationTest {

    @Test
    void dockerImageCopiesTheBuiltJarByVersionIndependentWildcard() throws IOException {
        String dockerfile = read("Dockerfile");

        assertThat(dockerfile).contains("COPY --from=build /workspace/target/motion-java-*.jar /app/app.jar");
        assertThat(dockerfile).doesNotContain("motion-java-0.1.0-PHASE2.jar");
    }

    @Test
    void javaEnvTemplateDoesNotExportAnEmptyJdbcUrl() throws IOException {
        String template = read(".env.example");

        assertThat(template).contains("# MOTION_JDBC_URL=jdbc:sqlite:");
        assertThat(template).doesNotContainPattern("(?m)^MOTION_JDBC_URL=\\s*$");
    }

    @Test
    void springdocAndActuatorAreDisabledByDefaultAndInProduction() throws IOException {
        String application = read("src/main/resources/application.yml");
        String production = read("src/main/resources/application-production.yml");

        assertThat(application).contains("include: none");
        assertThat(application).contains("enabled: ${SPRINGDOC_ENABLED:false}");
        assertThat(production).contains("springdoc:");
        assertThat(production).contains("management:");
        assertThat(production).contains("include: none");
    }

    @Test
    void datasourceConfigurationNormalizesEmptyJdbcUrls() throws IOException {
        assertThat(read("src/main/java/com/motion/backend/config/SqliteDataSourceConfiguration.java"))
                .contains("normalizes an explicitly empty")
                .contains("StringUtils.hasText(properties.getUrl())");
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath));
    }
}
