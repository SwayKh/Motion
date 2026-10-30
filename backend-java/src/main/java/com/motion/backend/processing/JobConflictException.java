package com.motion.backend.processing;

/** Maps to the active-job conflict response. */
public class JobConflictException extends RuntimeException {

    public JobConflictException() {
        super("video already has an active job");
    }
}
