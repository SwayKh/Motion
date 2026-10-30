package com.motion.backend.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.config.MotionProperties;
import com.motion.backend.media.MediaRecord;
import com.motion.backend.media.MediaStore;
import com.motion.backend.processing.JobExecutionRegistry.ExecutionHandle;
import com.motion.backend.processing.JobExecutionRegistry.StopReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Worker-side orchestration around the unchanged FFmpeg engine.
 *
 * <p>Every database write is short and guarded by the execution lease. FFmpeg runs outside any
 * transaction, progress is throttled, and terminal decisions are made from the persisted row so a
 * cancellation request can never be overwritten by a stale completion.</p>
 */
@Component
public class JobExecutionService {

    private static final Logger log = LoggerFactory.getLogger(JobExecutionService.class);
    private static final double MAX_PROGRESS_BEFORE_COMPLETION = 99D;

    private final JobStore jobs;
    private final MediaStore mediaStore;
    private final ProcessingService processing;
    private final JobExecutionRegistry registry;
    private final JobArtifactCleaner cleaner;
    private final ObjectMapper mapper;
    private final MotionProperties properties;

    public JobExecutionService(JobStore jobs, MediaStore mediaStore, ProcessingService processing,
                               JobExecutionRegistry registry, JobArtifactCleaner cleaner,
                               ObjectMapper mapper, MotionProperties properties) {
        this.jobs = jobs;
        this.mediaStore = mediaStore;
        this.processing = processing;
        this.registry = registry;
        this.cleaner = cleaner;
        this.mapper = mapper;
        this.properties = properties;
    }

    /** Executes one claimed job. This method never propagates a job failure to the worker loop. */
    public void execute(JobLease lease) {
        String jobId = lease.job().id();
        String token = lease.token();
        ExecutionHandle handle = null;
        boolean restoreInterrupt = false;
        try {
            handle = registry.register(jobId, token, lease.workerId(), Thread.currentThread());
            if (!handle.matches(token)) {
                log.warn("job={} already has execution token {}; requeuing duplicate claim",
                        jobId, handle.token());
                jobs.requeueInterrupted(jobId, token);
                return;
            }
            if (handle.stopped() || !processingStateIsCurrent(lease)) {
                finalizeStop(lease, handle);
                return;
            }

            ProcessingRequest request = parseOperations(lease.job().operations());
            MediaRecord video = mediaStore.findById(lease.job().videoId())
                    .orElseThrow(() -> new ProcessingException("source file is no longer available"));
            verifyReferences(lease.job(), request);

            AtomicLong lastWriteNanos = new AtomicLong(Long.MIN_VALUE);
            AtomicReference<Double> lastProgress = new AtomicReference<>(0D);
            ProcessListener listener = new ProcessListener() {
                @Override
                public void started(Process process) {
                    registry.attachProcess(jobId, token, process);
                }

                @Override
                public void finished(Process process) {
                    registry.detachProcess(jobId, token, process);
                }
            };

            ProcessingResult result = processing.process(lease.job(), video, request,
                    value -> persistProgress(lease, value, lastWriteNanos, lastProgress), listener);

            if (isStopped(jobId, token, handle)) {
                cleaner.remove(jobId, result.outputPath());
                finalizeStop(lease, handle);
                return;
            }
            Optional<JobRecord> completed = jobs.complete(jobId, token, result.outputPath(),
                    result.encrypted(), result.encKey());
            if (completed.isPresent()) {
                return;
            }
            // Completion lost a race with cancellation or lease invalidation. Never leave output.
            cleaner.remove(jobId, result.outputPath());
            if (isStopped(jobId, token, handle)) {
                finalizeStop(lease, handle);
            } else {
                log.warn("job={} completion was rejected by a stale lease", jobId);
            }
        } catch (Throwable throwable) {
            restoreInterrupt = Thread.interrupted();
            if (handle == null || !handle.matches(token)) {
                log.error("job={} failed before its execution was registered", jobId, throwable);
                jobs.fail(jobId, token, failureMessage(throwable));
                return;
            }
            cleaner.remove(jobId, null);
            if (isStopped(jobId, token, handle)) {
                finalizeStop(lease, handle);
            } else if (restoreInterrupt) {
                // A shutdown interrupt can reach this path without a registry reason in rare races.
                jobs.requeueInterrupted(jobId, token);
            } else {
                log.warn("job={} processing failed: {}", jobId, failureMessage(throwable));
                jobs.fail(jobId, token, failureMessage(throwable));
            }
        } finally {
            if (handle != null && handle.matches(token)) {
                registry.unregister(jobId, token);
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean processingStateIsCurrent(JobLease lease) {
        Optional<JobLease> current = jobs.findLeaseById(lease.job().id());
        return current.isPresent()
                && lease.token().equals(current.get().token())
                && "processing".equals(current.get().job().status())
                && current.get().job().cancelRequestedAt() == null;
    }

    private ProcessingRequest parseOperations(String json) {
        if (json == null || json.isBlank()) {
            throw new ProcessingException("invalid saved operations");
        }
        try {
            ProcessingRequest request = mapper.readValue(json, ProcessingRequest.class);
            if (request.operations().isEmpty()) {
                throw new ProcessingException("invalid saved operations");
            }
            return request;
        } catch (IOException exception) {
            throw new ProcessingException("invalid saved operations", exception);
        }
    }

    private void verifyReferences(JobRecord job, ProcessingRequest request) {
        String owner = job.userId();
        if (owner == null) {
            return;
        }
        Set<String> references = new LinkedHashSet<>();
        references.add(job.videoId());
        references.addAll(ProcessingValidator.referencedVideoIds(job.videoId(), request));
        for (String reference : references) {
            MediaRecord media = mediaStore.findById(reference).orElse(null);
            if (media == null || media.userId() == null || !owner.equals(media.userId())) {
                throw new ProcessingException("a referenced video is not available");
            }
            Path original = media.originalPath() == null ? null : Path.of(media.originalPath());
            if (original == null || !Files.isRegularFile(original)) {
                throw new ProcessingException("a referenced video is not available");
            }
        }
    }

    private void persistProgress(JobLease lease, double raw, AtomicLong lastWriteNanos,
                                 AtomicReference<Double> lastProgress) {
        String jobId = lease.job().id();
        String token = lease.token();
        if (registry.stopped(jobId, token)) {
            return;
        }
        double value = Math.min(MAX_PROGRESS_BEFORE_COMPLETION, Math.max(0D, raw));
        Double previous = lastProgress.get();
        if (previous != null && value <= previous) {
            return;
        }
        long now = System.nanoTime();
        long interval = properties.jobProgressInterval().toNanos();
        if (lastWriteNanos.get() != Long.MIN_VALUE && now - lastWriteNanos.get() < interval) {
            return;
        }
        if (jobs.updateProgress(jobId, token, value)) {
            lastWriteNanos.set(now);
            lastProgress.set(value);
        }
    }

    private boolean isStopped(String jobId, String token, ExecutionHandle handle) {
        if (handle != null && handle.stopped()) {
            return true;
        }
        Optional<JobRecord> current = jobs.findById(jobId);
        return current.isPresent() && ("cancel_requested".equals(current.get().status())
                || "cancelled".equals(current.get().status()));
    }

    /** Finalizes a user cancellation or requeues an interrupted shutdown attempt. */
    private void finalizeStop(JobLease lease, ExecutionHandle handle) {
        String jobId = lease.job().id();
        String token = lease.token();
        Optional<JobRecord> current = jobs.findById(jobId);
        if (current.isEmpty() || JobState.isTerminal(current.get().status())) {
            return;
        }
        boolean userCancel = (handle != null && handle.reason() == StopReason.USER_CANCEL)
                || "cancel_requested".equals(current.get().status());
        cleaner.remove(jobId, current.get().outputPath());
        if (userCancel) {
            jobs.markCancelled(jobId, token);
        } else {
            jobs.requeueInterrupted(jobId, token);
        }
    }

    private static String failureMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? "video processing failed" : message;
    }
}
