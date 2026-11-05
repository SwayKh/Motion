package com.motion.backend.timeline;

import com.motion.backend.auth.RequestAuth;
import com.motion.backend.processing.JobResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** application {@code POST /api/timeline/render} endpoint. */
@RestController
@RequestMapping("/api/timeline/render")
public class TimelineRenderController {

    private final TimelineRenderService service;

    public TimelineRenderController(TimelineRenderService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse render(
            @RequestBody(required = false) TimelineRenderRequest request,
            HttpServletRequest servletRequest) {
        String userId = RequestAuth.require(servletRequest).user().id();
        return service.render(userId, request);
    }
}
