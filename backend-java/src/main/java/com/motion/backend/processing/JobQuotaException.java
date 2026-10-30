package com.motion.backend.processing;

/** Maps to the job-quota response. */
public class JobQuotaException extends RuntimeException {

    public JobQuotaException() {
        super("too many active jobs; try again later");
    }
}
