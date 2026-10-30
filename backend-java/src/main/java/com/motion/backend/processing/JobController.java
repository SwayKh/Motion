package com.motion.backend.processing;

import com.motion.backend.auth.RequestAuth;
import com.motion.backend.media.MediaFileServer;
import com.motion.backend.media.MediaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/** application job polling and output aliases. */
@RestController
public class JobController {

    private final ProcessingFacade facade;
    private final MediaFileServer files;

    public JobController(ProcessingFacade facade, MediaFileServer files) {
        this.facade = facade;
        this.files = files;
    }

    @GetMapping("/api/jobs")
    public ResponseEntity<List<JobResponse>> list(
            @RequestParam(name = "videoId", required = false) String videoId,
            HttpServletRequest request) {
        return ResponseEntity.ok(facade.list(userId(request), videoId));
    }

    @GetMapping("/api/jobs/{id}")
    public ResponseEntity<JobResponse> get(@PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.ok(facade.get(userId(request), id));
    }

    @PostMapping("/api/jobs/{id}/cancel")
    public ResponseEntity<JobResponse> cancel(@PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.ok(facade.cancel(userId(request), id));
    }

    @DeleteMapping("/api/jobs/{id}")
    public ResponseEntity<StatusResponse> delete(@PathVariable String id, HttpServletRequest request) {
        facade.delete(userId(request), id);
        return ResponseEntity.ok(new StatusResponse("ok"));
    }

    @GetMapping("/api/jobs/{id}/output")
    public ResponseEntity<StreamingResponseBody> output(@PathVariable String id, HttpServletRequest request)
            throws Exception {
        MediaService.ServableFile file = facade.output(userId(request), id);
        return files.serve(request, file, false);
    }

    @GetMapping("/api/jobs/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String id, HttpServletRequest request)
            throws Exception {
        MediaService.ServableFile file = facade.download(userId(request), id);
        return files.serve(request, file, true);
    }

    private static String userId(HttpServletRequest request) {
        return RequestAuth.require(request).user().id();
    }

    private record StatusResponse(String status) {
    }
}
