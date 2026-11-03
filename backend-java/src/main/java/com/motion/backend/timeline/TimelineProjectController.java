package com.motion.backend.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.auth.RequestAuth;
import com.motion.backend.web.api.JsonBodies;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Thin wire boundary for timeline-project CRUD; routes mirror Go exactly. */
@RestController
public class TimelineProjectController {

    private final TimelineProjectService service;
    private final ObjectMapper mapper;

    public TimelineProjectController(TimelineProjectService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/api/timeline-projects")
    public ResponseEntity<List<TimelineProject>> list(HttpServletRequest request) {
        String userId = RequestAuth.require(request).user().id();
        return ResponseEntity.ok(service.list(userId));
    }

    @PostMapping("/api/timeline-projects")
    public ResponseEntity<TimelineProject> create(HttpServletRequest request) {
        TimelineProject body = JsonBodies.read(request, mapper, TimelineProject.class);
        String userId = RequestAuth.require(request).user().id();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(userId, body));
    }

    @GetMapping("/api/timeline-projects/{id}")
    public ResponseEntity<TimelineProject> get(@PathVariable String id, HttpServletRequest request) {
        String userId = RequestAuth.require(request).user().id();
        return ResponseEntity.ok(service.get(userId, id));
    }

    @PutMapping("/api/timeline-projects/{id}")
    public ResponseEntity<TimelineProject> update(
            @PathVariable String id, HttpServletRequest request) {
        TimelineProject body = JsonBodies.read(request, mapper, TimelineProject.class);
        String userId = RequestAuth.require(request).user().id();
        return ResponseEntity.ok(service.update(userId, id, body));
    }

    @DeleteMapping("/api/timeline-projects/{id}")
    public ResponseEntity<Map<String, String>> delete(
            @PathVariable String id, HttpServletRequest request) {
        String userId = RequestAuth.require(request).user().id();
        service.delete(userId, id);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
