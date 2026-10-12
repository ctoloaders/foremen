package com.foremen.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-05-10b (R3.1, R3.2, D-bar-key, D-storage, D-uncovered): a single planned bar of a project
 * schedule, now keyed per WORKER — one bar per {@code (schedule, projectMember, workCategory)}.
 * Maps the {@code project_schedule_bars} table as reworked by changeset
 * {@code 156-alter-schedule-bars-per-worker.xml} (originally created by {@code 152}).
 *
 * <p>A single category may own several parallel bars (one per covering worker). {@link #projectMember}
 * is nullable: a null member marks the Uncovered_Category virtual bar (D-uncovered, R3.2), of which
 * at most one may exist per category (partial unique index, changeset 156-c).
 *
 * <p>Granularity is half-days (D-storage): {@link #startHalfDays} is the 1-based half-day from the
 * schedule anchor and {@link #durationHalfDays} the length in half-days; both are {@code >= 1} at the
 * DB level (CHECK constraints) and the service layer. The {@link #workCategory} FK is LAZY — a
 * category in use by a bar cannot be hard-deleted while the bar exists.
 */
@Entity
@Table(name = "project_schedule_bars",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_project_schedule_bars_schedule_member_category",
                columnNames = {"schedule_id", "project_member_id", "work_category_id"}))
@Getter
@Setter
@NoArgsConstructor
public class ProjectScheduleBarEntity extends BaseEntity {

    /** Owning schedule — the aggregate root; NOT NULL, ON DELETE CASCADE at the DB level. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private ProjectScheduleEntity schedule;

    /** The estimate work category this bar plans (R3.1); NOT NULL. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_category_id", nullable = false)
    private WorkCategoryEntity workCategory;

    /**
     * FOR-05-10b (R3.2, D-uncovered) — the worker this bar is assigned to; nullable. A null member
     * marks the Uncovered_Category virtual bar. FK is {@code ON DELETE CASCADE} (changeset 156-a).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_member_id")
    private ProjectMemberEntity projectMember;

    /** 1-based half-day from the anchor; CHECK start_half_days >= 1 (R3.1, D-storage). */
    @Column(name = "start_half_days", nullable = false)
    private Integer startHalfDays;

    /** Duration in half-days; CHECK duration_half_days >= 1 (R3.1, D-storage). */
    @Column(name = "duration_half_days", nullable = false)
    private Integer durationHalfDays;
}
