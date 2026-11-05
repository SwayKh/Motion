package com.motion.backend.timeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.auth.ApiException;
import com.motion.backend.media.MediaRecord;
import com.motion.backend.media.MediaService;
import com.motion.backend.processing.AsyncJobManager;
import com.motion.backend.processing.JobConflictException;
import com.motion.backend.processing.JobQuotaException;
import com.motion.backend.processing.JobRecord;
import com.motion.backend.processing.JobResponse;
import com.motion.backend.processing.JobStore;
import com.motion.backend.processing.ProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/** Strict render validation and immutable snapshot submission onto the Phase 5 job queue. */
@Component
public class TimelineRenderService {

    private static final Logger log = LoggerFactory.getLogger(TimelineRenderService.class);

    private final TimelineProjectService projects;
    private final MediaService mediaService;
    private final JobStore jobs;
    private final AsyncJobManager manager;
    private final ObjectMapper mapper;

    public TimelineRenderService(
            TimelineProjectService projects,
            MediaService mediaService,
            JobStore jobs,
            AsyncJobManager manager,
            ObjectMapper mapper) {
        this.projects = projects;
        this.mediaService = mediaService;
        this.jobs = jobs;
        this.manager = manager;
        this.mapper = mapper;
    }

    public JobResponse render(String userId, TimelineRenderRequest request) {
        if (request == null || (isBlank(request.projectId()) && request.project() == null)) {
            throw new ApiException(400, "a project or projectId is required");
        }

        TimelineProject source = !isBlank(request.projectId())
                ? projects.get(userId, request.projectId())
                : request.project();
        TimelineProject project = TimelineNormalizer.normalize(source);
        TimelineAssetResolver resolver = resolver(userId);

        List<TimelineValidationError> errors = TimelineValidator.validateForRender(project, resolver);
        if (!errors.isEmpty()) {
            throw new TimelineValidationException(400, errors);
        }

        TimelineRenderPlan plan;
        try {
            plan = TimelineRenderPlanBuilder.build(project, resolver);
        } catch (ProcessingException exception) {
            throw new ApiException(422, exception.getMessage());
        }

        MediaRecord primary;
        try {
            primary = mediaService.requireOwned(userId, plan.primaryVideoId());
        } catch (ApiException exception) {
            throw new ApiException(400, "the primary video is not available");
        }
        if (isBlank(primary.originalPath()) || !sourceExists(primary.originalPath())) {
            throw new ApiException(409, "the original file is no longer available");
        }

        String operations;
        try {
            operations = mapper.writeValueAsString(plan.request());
        } catch (JsonProcessingException exception) {
            log.error("serializing timeline render snapshot", exception);
            throw new ApiException(500, "failed to save render request");
        }

        JobRecord queued;
        try {
            queued = jobs.createQueued(
                    plan.primaryVideoId(),
                    primary.originalName() == null ? "" : primary.originalName(),
                    primary.originalPath(),
                    operations,
                    userId);
        } catch (JobConflictException exception) {
            throw new ApiException(409, "the source video already has an active job");
        } catch (JobQuotaException exception) {
            throw new ApiException(429, "too many active jobs; try again later");
        } catch (RuntimeException exception) {
            log.error("queueing timeline render", exception);
            throw new ApiException(500, "failed to queue render");
        }

        manager.notifyJobs();
        return JobResponse.from(queued, mapper);
    }

    /** Mirrors the ownership-only resolver: stream details are verified by the worker. */
    private TimelineAssetResolver resolver(String userId) {
        return id -> {
            if (isBlank(id)) {
                return null;
            }
            MediaRecord media;
            try {
                media = mediaService.requireOwned(userId, id);
            } catch (ApiException exception) {
                return null;
            }
            if (isBlank(media.originalPath())) {
                return null;
            }
            return new TimelineAsset(media.id(), media.duration(), true, true);
        };
    }

    private static boolean sourceExists(String path) {
        try {
            return Files.exists(Path.of(path));
        } catch (InvalidPathException exception) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
