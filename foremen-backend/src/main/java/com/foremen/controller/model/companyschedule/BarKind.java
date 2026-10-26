package com.foremen.controller.model.companyschedule;

/**
 * The kind of a {@link ProjectedBar} in the cross-project staffing projection
 * (FOR-07-01-cross-project-schedule-core, Requirement 1.7).
 *
 * <p>The projection combines two distinct bar sources into one member-indexed read model:
 *
 * <ul>
 *   <li>{@link #WORKER_CATEGORY} — a {@code Worker_Category_Bar}, derived from a non-null-member
 *       {@code project_schedule_bars} row: a WORKER's planned bar pinned to a {@code WorkCategory}
 *       over a half-day range. Participates in per-worker overlap/conflict detection.</li>
 *   <li>{@link #FOREMAN_SPAN} — a {@code Foreman_Span_Bar}, a synthesized bar spanning an entire
 *       project a FOREMAN leads (from {@code project.startDate} to {@code project.endDate}). Carries
 *       no work category and no half-day fields, and never participates in conflict detection.</li>
 * </ul>
 */
public enum BarKind {
    WORKER_CATEGORY,
    FOREMAN_SPAN
}
