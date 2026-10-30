package com.motion.backend.processing;

/** Outcome of an atomic cancellation request, with the row as it looked at that instant. */
public record CancelResult(Outcome outcome, String token, JobRecord job) {

    public enum Outcome {
        QUEUED_CANCELLED,
        RUNNING_CANCEL_REQUESTED,
        NOT_CANCELLABLE
    }

    public static CancelResult queued(String token, JobRecord job) {
        return new CancelResult(Outcome.QUEUED_CANCELLED, token, job);
    }

    public static CancelResult running(String token, JobRecord job) {
        return new CancelResult(Outcome.RUNNING_CANCEL_REQUESTED, token, job);
    }

    public static CancelResult notCancellable() {
        return new CancelResult(Outcome.NOT_CANCELLABLE, null, null);
    }

    public boolean cancellable() {
        return outcome != Outcome.NOT_CANCELLABLE;
    }
}
