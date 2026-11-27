package com.foremen.controller.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foremen.dao.model.RoomsImportStatus;

/**
 * FOR-05-02 (Requirements 2.1, 2.2, 2.6) — the polled state of a rooms auto-import job. {@code stage}
 * is the progress step (same value set as {@code status}); {@code result} is present only when
 * {@code COMPLETED}, {@code failureReason} only when {@code FAILED}, {@code appliedAt} only once a room
 * was created from the job.
 *
 * <p>FOR-05-04 (Requirement 2.3): {@code mode} ({@code ROOMS_ONLY}/{@code ESTIMATE}), {@code phase}
 * ({@code ROOMS}/{@code ESTIMATE}, derived from the status),
 * {@code roomsSavedAt} and {@code roomsCreated} of the SAVING_ROOMS stage. For a completed
 * {@code ESTIMATE} job {@code result} also carries the {@code estimate} summary.
 *
 * <p>FOR-05-04 rework (Requirement 26.2): {@code actionLog} (newest first) and the progress counters
 * {@code roomsTotal}, {@code roomsDone}, {@code worksTotal}, {@code worksDone}; a counter unknown for the
 * current stage is serialized as {@code null}.
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
        Map<String, Object> result,
        String mode,
        String phase,
        LocalDateTime roomsSavedAt,
        Integer roomsCreated,
        @JsonInclude(JsonInclude.Include.ALWAYS) List<Map<String, Object>> actionLog,
        @JsonInclude(JsonInclude.Include.ALWAYS) Integer roomsTotal,
        @JsonInclude(JsonInclude.Include.ALWAYS) Integer roomsDone,
        @JsonInclude(JsonInclude.Include.ALWAYS) Integer worksTotal,
        @JsonInclude(JsonInclude.Include.ALWAYS) Integer worksDone) {
}
