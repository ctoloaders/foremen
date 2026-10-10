package com.foremen.dao.model;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-05-10 (Requirements 6.1, 6.3): the per-project Planning Gantt (Harmonogram) aggregate root.
 * Maps the {@code project_schedules} table (changeset {@code 152-create-project-schedules.xml}).
 *
 * <p>At most one schedule per project — the {@code project} FK is NOT NULL and UNIQUE (R6.1). The
 * {@link #version} field is the JPA {@code @Version} optimistic-lock counter bumped exactly once per
 * committed write (R11); a stale version is mapped to a 409 conflict at the service layer. There is
 * no rate column: the Daily_Output_Rate is internal config only and is never persisted (R9.3).
 *
 * <p>{@link #bars} is the owned child collection (one bar per work category, R6.2) with
 * {@code cascade = ALL} and {@code orphanRemoval = true}, so clearing or removing a bar from the
 * collection deletes its row and saving the aggregate persists the bars.
 */
@Entity
@Table(name = "project_schedules")
@Getter
@Setter
@NoArgsConstructor
public class ProjectScheduleEntity extends BaseEntity {

    /** Owner FK to the project, NOT NULL and UNIQUE — one schedule per project (R6.1). */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, unique = true)
    private ProjectEntity project;

    /** JPA optimistic-lock counter, defaulted to 0; bumped once per committed write (R11). */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * The owned planned bars of this schedule (one per work category, R6.2). Cascades all operations
     * and removes orphans, so the service mutates this collection directly and saves the aggregate.
     */
    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProjectScheduleBarEntity> bars = new ArrayList<>();
}
