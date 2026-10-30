package com.motion.backend.processing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.auth.ApiException;
import com.motion.backend.media.MediaRecord;
import com.motion.backend.media.MediaService;
import com.motion.backend.media.MediaStore;
import com.motion.backend.media.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * HTTP-facing boundary for the asynchronous job system.
 *
 * <p>Validation, ownership checks, and durable queue insertion stay on the request thread. FFmpeg
 * never runs there: workers own execution, progress, terminal transitions, and cancellation.</p>
 */
@Component
public class ProcessingFacade {

    private static final Logger log = LoggerFactory.getLogger(ProcessingFacade.class);

    private final MediaService mediaService;
    private final MediaStore mediaStore;
    private final StorageService storage;
    private final JobStore jobs;
    private final AsyncJobManager manager;
    private final ObjectMapper mapper;

    public ProcessingFacade(MediaService mediaService, MediaStore mediaStore, StorageService storage,
                            JobStore jobs, AsyncJobManager manager, ObjectMapper mapper) {
        this.mediaService = mediaService;
        this.mediaStore = mediaStore;
        this.storage = storage;
        this.jobs = jobs;
        this.manager = manager;
        this.mapper = mapper;
    }

    /** Validates and persists a queued job, then returns immediately without running FFmpeg. */
    public JobResponse submit(String userId, String videoId, ProcessingRequest request) {
        MediaRecord video = mediaService.requireOwned(userId, videoId);
        if (video.originalPath() == null || video.originalPath().isBlank()
                || !Files.isRegularFile(Path.of(video.originalPath()))) {
            throw new ApiException(409, "the original file is no longer available");
        }

        try {
            ProcessingValidator.validate(video, request);
        } catch (ProcessingException exception) {
            throw new ApiException(400, exception.getMessage());
        }

        for (String reference : ProcessingValidator.referencedVideoIds(videoId, request)) {
            MediaRecord referenced = mediaStore.findById(reference).orElse(null);
            if (referenced == null || referenced.userId() == null
                    || !referenced.userId().equals(userId)) {
                throw new ApiException(400, "a referenced video is not available");
            }
        }

        String operations;
        try {
            operations = mapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            log.error("serializing process request for video={}", videoId, exception);
            throw new ApiException(500, "failed to save processing request");
        }

        JobRecord queued;
        try {
            queued = jobs.createQueued(videoId, videoName(video), video.originalPath(),
                    operations, userId);
        } catch (JobConflictException exception) {
            throw new ApiException(409, exception.getMessage());
        } catch (JobQuotaException exception) {
            throw new ApiException(429, exception.getMessage());
        }
        manager.notifyJobs();
        return response(queued);
    }

    public List<JobResponse> list(String userId, String videoId) {
        if (videoId != null && !videoId.isBlank()) {
            mediaService.requireOwned(userId, videoId);
            return jobs.listForVideo(videoId).stream().map(this::response).toList();
        }
        return jobs.listByUser(userId).stream().map(this::response).toList();
    }

    public JobResponse get(String userId, String jobId) {
        return response(requireJob(userId, jobId, "job not found"));
    }

    /** Queued jobs cancel immediately; running jobs enter the cancel_requested wire state. */
    public JobResponse cancel(String userId, String jobId) {
        requireJob(userId, jobId, "job not found");
        CancelResult result = jobs.requestCancel(jobId);
        if (!result.cancellable()) {
            throw new ApiException(409, "job has already finished");
        }
        if (result.outcome() == CancelResult.Outcome.RUNNING_CANCEL_REQUESTED) {
            manager.requestCancel(jobId, result.token());
        }
        // Return the snapshot taken inside the cancel transaction. The worker may finalise to
        // cancelled before we respond, but the cancel response reports cancel_requested.
        return response(result.job());
    }

    public void delete(String userId, String jobId) {
        JobRecord job = requireJob(userId, jobId, "job not found");
        if (JobStore.active(job.status())) {
            throw new ApiException(409, "cancel the job before deleting it");
        }
        storage.removeQuietly(job.outputPath());
        jobs.deleteAndResetVideo(job);
    }

    public MediaService.ServableFile output(String userId, String jobId) {
        return jobArtifact(userId, jobId, false);
    }

    public MediaService.ServableFile download(String userId, String jobId) {
        return jobArtifact(userId, jobId, true);
    }

    private MediaService.ServableFile jobArtifact(String userId, String jobId, boolean download) {
        JobRecord job = requireJob(userId, jobId, "processed output is not available");
        if (!"completed".equals(job.status()) || job.outputPath() == null || job.outputPath().isBlank()) {
            throw new ApiException(404, "processed output is not available");
        }
        return mediaService.storedFile(job.outputPath(), jobOutputFilename(job),
                job.encrypted(), job.encKey());
    }

    private JobRecord requireJob(String userId, String jobId, String notFoundMessage) {
        JobRecord job = jobs.findById(jobId).orElseThrow(() -> new ApiException(404, notFoundMessage));
        if (!jobs.accessible(job, userId)) {
            throw new ApiException(404, notFoundMessage);
        }
        return job;
    }

    private JobResponse response(JobRecord job) {
        return JobResponse.from(job, mapper);
    }

    private static String videoName(MediaRecord video) {
        return video.originalName() == null || video.originalName().isBlank()
                ? video.id() : video.originalName();
    }

    /** Mirrors the {@code jobOutputFilename}: base name, {@code -edited-}, and 8-char job prefix. */
    private static String jobOutputFilename(JobRecord job) {
        String name = job.videoName() == null ? "" : job.videoName();
        String base = name.substring(0, Math.max(0, name.length() - extensionLength(name)));
        if (base.isBlank() || ".".equals(base)) {
            base = job.id();
        }
        String extension = extension(job.outputPath() == null ? "" : job.outputPath());
        String prefix = job.id().length() <= 8 ? job.id() : job.id().substring(0, 8);
        return base + "-edited-" + prefix + extension;
    }

    private static int extensionLength(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? 0 : filename.length() - dot;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }
}
