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
 * FOR-05-10 (Requirement 6.2): a single planned bar of a project schedule — at most one per
 * (schedule, work category). Maps the {@code project_schedule_bars} table (changeset
 * {@code 152-create-project-schedules.xml}).
 *
 * <p>{@link #startDay} is the 1-based calendar day from the schedule anchor and {@link #durationDays}
 * the length in calendar days; both are {@code >= 1} at the DB level (CHECK constraints) and the
 * service layer (R6.2, R7.2). The {@link #workCategory} FK is LAZY — a category in use by a bar
 * cannot be hard-deleted while the bar exists.
 */
@Entity
@Table(name = "project_schedule_bars",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_project_schedule_bars_schedule_category",
                columnNames = {"schedule_id", "work_category_id"}))
@Getter
@Setter
@NoArgsConstructor
public class ProjectScheduleBarEntity extends BaseEntity {

    /** Owning schedule — the aggregate root; NOT NULL, ON DELETE CASCADE at the DB level. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private ProjectScheduleEntity schedule;

    /** The estimate work category this bar plans (R6.2); NOT NULL. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_category_id", nullable = false)
    private WorkCategoryEntity workCategory;

    /** 1-based calendar day from the anchor; CHECK start_day >= 1 (R6.2). */
    @Column(name = "start_day", nullable = false)
    private Integer startDay;

    /** Duration in calendar days; CHECK duration_days >= 1 (R6.2, R7.2). */
    @Column(name = "duration_days", nullable = false)
    private Integer durationDays;
}
