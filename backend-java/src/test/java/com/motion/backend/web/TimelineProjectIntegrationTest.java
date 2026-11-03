package com.motion.backend.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-HTTP tests for timeline project CRUD, revisions, validation, and ownership. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "motion.rate-limit.login=10000",
                "motion.rate-limit.register=10000",
                "motion.rate-limit.reset=10000",
                "motion.rate-limit.verify=10000"
        }
)
class TimelineProjectIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("timeline.db"));
        registry.add("motion.storage.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("motion.storage.upload-dir", () -> tempDir.resolve("uploads").toString());
        registry.add("motion.storage.output-dir", () -> tempDir.resolve("outputs").toString());
        registry.add("motion.storage.temp-dir", () -> tempDir.resolve("data/tmp").toString());
        registry.add("motion.storage.preview-dir", () -> tempDir.resolve("data/previews").toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void timelineCrudRevisionAndOwnership() throws Exception {
        TestHttpClient owner = MediaTestSupport.authenticatedClient(restTemplate);
        TestHttpClient other = MediaTestSupport.authenticatedClient(restTemplate);

        String project = projectJson("Draft", 0);
        ResponseEntity<String> created = owner.postWithCsrf("/api/timeline-projects", project);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json = mapper.readTree(created.getBody());
        String id = json.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(json.path("revision").asInt()).isEqualTo(1);
        assertThat(json.path("tracks").get(0).path("visible").asBoolean()).isTrue();
        assertThat(created.getBody()).startsWith("{\"id\":\"" + id + "\",\"name\":\"Draft\",");
        assertThat(created.getBody()).endsWith("\n");

        ResponseEntity<String> listed = owner.get("/api/timeline-projects");
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(listed.getBody()).get(0).path("id").asText()).isEqualTo(id);

        ResponseEntity<String> loaded = owner.get("/api/timeline-projects/" + id);
        assertThat(loaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loaded.getBody()).contains("\"schemaVersion\":1");

        ResponseEntity<String> updated = owner.putWithCsrf(
                "/api/timeline-projects/" + id, projectJson("Renamed", 1));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(updated.getBody()).path("revision").asInt()).isEqualTo(2);
        assertThat(mapper.readTree(updated.getBody()).path("name").asText()).isEqualTo("Renamed");

        ResponseEntity<String> stale = owner.putWithCsrf(
                "/api/timeline-projects/" + id, projectJson("Stale", 1));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).isEqualTo(
                "{\"error\":\"this timeline was changed in another session; reload it before saving\"}\n");

        assertThat(other.get("/api/timeline-projects/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(other.putWithCsrf("/api/timeline-projects/" + id, projectJson("Other", 2))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(other.deleteWithCsrf("/api/timeline-projects/" + id)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> deleted = owner.deleteWithCsrf("/api/timeline-projects/" + id);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deleted.getBody()).isEqualTo("{\"status\":\"ok\"}\n");
        assertThat(owner.get("/api/timeline-projects/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void timelineSaveIsLenientButFatalValidationFails() throws Exception {
        TestHttpClient client = MediaTestSupport.authenticatedClient(restTemplate);

        ResponseEntity<String> fatal = client.postWithCsrf(
                "/api/timeline-projects", projectJson("", 0));
        assertThat(fatal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode error = mapper.readTree(fatal.getBody());
        assertThat(error.path("error").asText()).isEqualTo("a project name is required");
        assertThat(error.path("validationErrors").get(0).path("code").asText()).isEqualTo("missing_name");

        String draft = """
                {"name":"Draft with invalid clip","schemaVersion":1,"tracks":[
                  {"id":"v1","type":"video","order":0,"visible":true,"clips":[
                    {"id":"c1","kind":"video","sourceIn":3,"sourceOut":1,"timelineStart":0}
                  ]}
                ],"render":{}}
                """;
        ResponseEntity<String> saved = client.postWithCsrf("/api/timeline-projects", draft);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(mapper.readTree(saved.getBody()).path("tracks").get(0)
                .path("clips").get(0).path("volume").asDouble()).isEqualTo(1D);
    }

    private static String projectJson(String name, int revision) {
        return """
                {"name":"%s","schemaVersion":1,"revision":%d,"tracks":[
                  {"id":"v1","type":"video","order":0,"visible":true,"clips":[
                    {"id":"c1","kind":"video","assetId":"asset-1","sourceIn":0,"sourceOut":2,"timelineStart":0}
                  ]}
                ],"render":{}}
                """.formatted(name, revision);
    }
}
