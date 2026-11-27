package com.foremen.dao.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-05-02 (Requirements 2.1, 2.3, 2.4, 16.1, 19.1; design B3) — an asynchronous AI rooms-import
 * job. Maps {@code rooms_import_jobs} (changeset {@code 213-create-rooms-import-jobs.xml}). At most
 * one row per project (UNIQUE {@code project_id}).
 *
 * <p>{@code inputs}, {@code result} and {@code stageLog} are JSONB; they are held as generic
 * structures here so the entity does not depend on the result model records.
 */
@Entity
@Table(name = "rooms_import_jobs")
@Getter
@Setter
@NoArgsConstructor
public class RoomsImportJobEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private UserEntity creator;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private RoomsImportStatus status;

    @Column(name = "prompt_text", columnDefinition = "text")
    private String promptText;

    /** Manifest {@code [{fileName, contentType, sizeBytes, objectKey, kind}]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inputs", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> inputs;

    /** The {@code ImportResult} document, set on completion. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(name = "failure_reason", length = 64)
    private String failureReason;

    /** {@code [{stage, at}]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stage_log", columnDefinition = "jsonb")
    private List<Map<String, Object>> stageLog;

    @Column(name = "rooms_detected")
    private Integer roomsDetected;

    @Column(name = "warnings_count")
    private Integer warningsCount;

    @Column(name = "model_id", length = 64)
    private String modelId;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** Set when a room is created with this job's id. */
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    /** FOR-05-04: {@code ROOMS_ONLY} (default) or {@code ESTIMATE}. */
    @Column(name = "mode", nullable = false, length = 16)
    private String mode = "ROOMS_ONLY";

    /** FOR-05-04: skip the ROOMS phase and plan over the project's existing rooms. */
    @Column(name = "use_existing_rooms", nullable = false)
    private boolean useExistingRooms = false;

    @Column(name = "package_code", length = 64)
    private String packageCode;

    /** End of the SAVING_ROOMS stage. */
    @Column(name = "rooms_saved_at")
    private LocalDateTime roomsSavedAt;

    @Column(name = "rooms_created")
    private Integer roomsCreated;

    /** FOR-05-04 rework: bounded action log (newest first, at most 200 entries). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action_log", columnDefinition = "jsonb")
    private List<Map<String, Object>> actionLog;

    @Column(name = "rooms_total")
    private Integer roomsTotal;

    @Column(name = "rooms_done")
    private Integer roomsDone;

    @Column(name = "works_total")
    private Integer worksTotal;

    @Column(name = "works_done")
    private Integer worksDone;
}
