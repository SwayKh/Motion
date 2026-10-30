package com.motion.backend.processing;

/** A job row together with the lease token issued by its current worker. */
public record JobLease(JobRecord job, String token, int attempt, String workerId) {

    public boolean cancellationRequested() {
        return "cancel_requested".equals(job.status()) || job.cancelRequestedAt() != null;
    }
}
