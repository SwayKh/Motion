package com.motion.backend.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that the existing frontend is served byte-for-byte without an SPA fallback. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.profiles.active=test"
)
class StaticFileIntegrationTest {

    @TempDir
    static Path tempDir;

    private static final Path WEB_ROOT = Path.of("..").resolve("web").toAbsolutePath().normalize();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("motion.static-dir", () -> WEB_ROOT.toString());
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("static.db"));
        registry.add("motion.storage.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("motion.storage.upload-dir", () -> tempDir.resolve("uploads").toString());
        registry.add("motion.storage.output-dir", () -> tempDir.resolve("outputs").toString());
        registry.add("motion.storage.temp-dir", () -> tempDir.resolve("data/tmp").toString());
        registry.add("motion.storage.preview-dir", () -> tempDir.resolve("data/previews").toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void servesExistingFrontendAssetsFromConfiguredDirectory() throws Exception {
        TestHttpClient client = new TestHttpClient(restTemplate);

        ResponseEntity<String> root = client.get("/");
        assertThat(root.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(root.getBody()).contains("url=/landing.html");

        ResponseEntity<byte[]> stylesheet = client.getBytes("/styles.css", new HttpHeaders());
        assertThat(stylesheet.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stylesheet.getHeaders().getContentType().toString())
                .startsWith(MediaType.valueOf("text/css").toString());
        assertThat(new String(stylesheet.getBody())).contains(":root");

        ResponseEntity<byte[]> script = client.getBytes("/app.js", new HttpHeaders());
        assertThat(script.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(script.getBody())).contains("loadVideos");

        ResponseEntity<byte[]> favicon = client.getBytes("/favicon.svg", new HttpHeaders());
        assertThat(favicon.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(favicon.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).contains("image/svg+xml");
    }

    @Test
    void staticAssetsRetainLastModifiedAndRangeBehavior() {
        TestHttpClient client = new TestHttpClient(restTemplate);

        ResponseEntity<byte[]> full = client.getBytes("/styles.css", new HttpHeaders());
        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(full.getHeaders().getLastModified()).isPositive();
        assertThat(full.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isNull();

        HttpHeaders range = new HttpHeaders();
        range.set(HttpHeaders.RANGE, "bytes=0-15");
        ResponseEntity<byte[]> partial = client.getBytes("/styles.css", range);
        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(partial.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .startsWith("bytes 0-15/");
        assertThat(partial.getBody()).hasSize(16);
    }

    @Test
    void missingStaticPathReturnsPlainTextFileServer404InsteadOfSpaFallback() {
        ResponseEntity<String> missing = new TestHttpClient(restTemplate).get("/does-not-exist.js");

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).isEqualTo("404 page not found\n");
    }

    @Test
    void staticHandlerDoesNotInterceptExistingApiRoutes() {
        ResponseEntity<String> config = new TestHttpClient(restTemplate).get("/api/config");

        assertThat(config.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(config.getBody()).contains("\"maxUploadBytes\"");
    }
}
