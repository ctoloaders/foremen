package com.foremen.controller.model.companyschedule;

/**
 * One legend entry of the cross-project staffing projection
 * (FOR-07-01-cross-project-schedule-core, Requirement 3.3) — a project appearing in the projection
 * together with its deterministic {@code Project_Color} index, so the consumer can render a stable
 * legend. The {@link #colorIndex()} is the same index used by every {@link ProjectedBar} of this
 * project (Requirement 3.1) and is deterministic by project id across requests (Requirement 3.2).
 *
 * @param projectId   the project id
 * @param projectName the project name
 * @param colorIndex  the project's deterministic {@code Project_Color} index
 */
public record ProjectLegendEntry(
        long projectId,
        String projectName,
        int colorIndex
) {}
