package com.foremen.controller.model;

import java.time.LocalDateTime;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foremen.dao.model.RoomsImportStatus;

/**
 * FOR-05-02 (Requirements 2.1, 2.2, 2.6) — the polled state of a rooms auto-import job. {@code stage}
 * is the progress step (same value set as {@code status}); {@code result} is present only when
 * {@code COMPLETED}, {@code failureReason} only when {@code FAILED}, {@code appliedAt} only once a room
 * was created from the job.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoomsImportJobDto(
        Long jobId,
        Long projectId,
        RoomsImportStatus status,
        RoomsImportStatus stage,
        String failureReason,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        LocalDateTime appliedAt,
        Map<String, Object> result) {
}
