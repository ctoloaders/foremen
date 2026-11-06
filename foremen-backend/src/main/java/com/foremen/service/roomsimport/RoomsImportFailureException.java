package com.foremen.service.roomsimport;

/**
 * FOR-05-02 — a job-level pipeline failure carrying the stable {@code error.roomsimport.failed.*}
 * reason code stored in {@code failure_reason}. Never carries user content.
 */
public class RoomsImportFailureException extends RuntimeException {

    private final String reason;

    public RoomsImportFailureException(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    public RoomsImportFailureException(String reason) {
        this(reason, null);
    }

    public String getReason() {
        return reason;
    }
}
