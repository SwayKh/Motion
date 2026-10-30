package com.motion.backend.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.auth.RequestAuth;
import com.motion.backend.web.api.JsonBodies;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin wire boundary for process requests; all FFmpeg work stays behind the facade. */
@RestController
public class ProcessingController {

    private final ProcessingFacade facade;
    private final ObjectMapper mapper;

    public ProcessingController(ProcessingFacade facade, ObjectMapper mapper) {
        this.facade = facade;
        this.mapper = mapper;
    }

    @PostMapping("/api/videos/{id}/process")
    public ResponseEntity<JobResponse> process(@PathVariable String id, HttpServletRequest request) {
        ProcessingRequest body = JsonBodies.read(request, mapper, ProcessingRequest.class);
        String userId = RequestAuth.require(request).user().id();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(facade.submit(userId, id, body));
    }
}
