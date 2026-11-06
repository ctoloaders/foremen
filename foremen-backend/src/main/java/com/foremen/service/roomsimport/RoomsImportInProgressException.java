package com.foremen.service.roomsimport;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.exception.ForemenApiException;

/**
 * FOR-05-02 (Requirements 1.8, 19.2-19.4) — {@code 429 error.roomsimport.in_progress}. Carries the
 * named {@code params} ({@code jobId}, {@code stage}, {@code startedAt}) so the controller layer can
 * build the envelope and the {@code Retry-After} header (task 4.2); the positional
 * {@code messageParams} follow the same order.
 */
public class RoomsImportInProgressException extends ForemenApiException {

    /** Fixed estimate (seconds) sent in the {@code Retry-After} header. */
    public static final int RETRY_AFTER_SECONDS = 30;

    private final Long jobId;
    private final RoomsImportStatus stage;
    private final LocalDateTime startedAt;

    public RoomsImportInProgressException(Long jobId, RoomsImportStatus stage, LocalDateTime startedAt) {
        super(HttpStatus.TOO_MANY_REQUESTS, RoomsImportErrors.IN_PROGRESS, jobId, stage, startedAt);
        this.jobId = jobId;
        this.stage = stage;
        this.startedAt = startedAt;
        Map<String, Object> named = new LinkedHashMap<>();
        named.put("jobId", jobId);
        named.put("stage", stage == null ? null : stage.name());
        named.put("startedAt", startedAt == null ? null : startedAt.toString());
        withParams(named);
        withHeader("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));
    }

    public Long getJobId() {
        return jobId;
    }

    public RoomsImportStatus getStage() {
        return stage;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }
}
