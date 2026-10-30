package com.motion.backend.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Startup and shutdown recovery for interrupted job attempts.
 *
 * <p>No FFmpeg process from a previous JVM is assumed alive. Known artifacts are removed and the old
 * lease is invalidated before a job is requeued, so an orphaned process cannot complete the new
 * attempt. Terminal history is never rewritten.</p>
 */
@Component
public class JobRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(JobRecoveryService.class);

    private final JobStore jobs;
    private final JobArtifactCleaner cleaner;
    private final ObjectMapper mapper;
    private final MediaInputProvider inputs;

    public JobRecoveryService(JobStore jobs, JobArtifactCleaner cleaner, ObjectMapper mapper,
                              MediaInputProvider inputs) {
        this.jobs = jobs;
        this.cleaner = cleaner;
        this.mapper = mapper;
        this.inputs = inputs;
    }

    /** Runs before workers start. */
    public void recoverOnStartup() {
        // No workspace can be active yet in this JVM; remove plaintext left by a killed attempt.
        inputs.sweepAbandonedWorkspaces();
        recover(jobs.listInterrupted(), "startup");
    }

    /** Final safety sweep after graceful worker shutdown. */
    public void requeueRemainingAfterShutdown() {
        recover(jobs.listInterrupted(), "shutdown");
    }

    private void recover(List<JobLease> interrupted, String phase) {
        for (JobLease lease : interrupted) {
            JobRecord job = lease.job();
            cleaner.remove(job.id(), job.outputPath());
            if ("cancel_requested".equals(job.status())) {
                jobs.markCancelled(job.id(), lease.token());
                log.info("{} recovery finalized cancelled job={}", phase, job.id());
                continue;
            }
            if ("completed".equals(job.status()) || "failed".equals(job.status())
                    || "cancelled".equals(job.status())) {
                continue;
            }
            if (missingSource(job)) {
                jobs.fail(job.id(), lease.token(), "source file is no longer available");
                log.info("{} recovery failed job={}: source is missing", phase, job.id());
                continue;
            }
            if (!validOperations(job)) {
                jobs.fail(job.id(), lease.token(), "invalid saved operations");
                log.info("{} recovery failed job={}: invalid operations", phase, job.id());
                continue;
            }
            if (job.outputPath() != null && !job.outputPath().isBlank()) {
                jobs.fail(job.id(), lease.token(), "recovery found an incomplete output");
                log.info("{} recovery failed job={}: incomplete output", phase, job.id());
                continue;
            }
            if (jobs.requeueInterrupted(job.id(), lease.token())) {
                log.info("{} recovery requeued job={}", phase, job.id());
            }
        }
    }

    private static boolean missingSource(JobRecord job) {
        if (job.sourcePath() == null || job.sourcePath().isBlank()) {
            return true;
        }
        try {
            return !Files.isRegularFile(Path.of(job.sourcePath()));
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private boolean validOperations(JobRecord job) {
        String operations = job.operations();
        if (operations == null || operations.isBlank()) {
            return false;
        }
        try {
            return !mapper.readValue(operations, ProcessingRequest.class).operations().isEmpty();
        } catch (Exception exception) {
            return false;
        }
    }
}
