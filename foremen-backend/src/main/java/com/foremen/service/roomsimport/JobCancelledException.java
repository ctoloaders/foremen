package com.foremen.service.roomsimport;

/**
 * FOR-05-02 (Requirement 24.3) — the job row is gone (cancelled / replaced) or already terminal
 * (failed by the sweep). Thrown by the stage marker and the cancellation check; the worker swallows it
 * without writing a result or a notification.
 */
public class JobCancelledException extends RuntimeException {

    private final Long jobId;

    public JobCancelledException(Long jobId) {
        super("rooms import job " + jobId + " is cancelled");
        this.jobId = jobId;
    }

    public Long getJobId() {
        return jobId;
    }
}
