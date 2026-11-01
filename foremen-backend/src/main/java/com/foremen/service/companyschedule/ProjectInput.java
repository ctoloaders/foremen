package com.foremen.service.companyschedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One Accessible_Project flattened for the pure {@link CrossProjectScheduleProjector} (FOR-07-01).
 *
 * @param projectId   the project id
 * @param projectName the project name
 * @param startDate   the Schedule_Anchor ({@code null} for an Unanchored_Project)
 * @param endDate     the project end date ({@code null} when unknown)
 * @param workerBars  the Real_Bars of its members
 * @param foremen     the foreman-span sources (ACTIVE foremen, or managers acting as foreman)
 * @param memberRoles FOR-07-01 redesign — the project role code of every ACTIVE member, keyed by user
 *                    id; used to resolve each row's main role (MANAGER &gt; FOREMAN &gt; WORKER)
 */
public record ProjectInput(long projectId, String projectName,
                           LocalDate startDate, LocalDate endDate,
                           List<WorkerBarInput> workerBars,
                           List<ForemanInput> foremen,
                           Map<Long, String> memberRoles) {

    /** A project input without member-role information (the pre-redesign shape). */
    public ProjectInput(long projectId, String projectName, LocalDate startDate, LocalDate endDate,
                        List<WorkerBarInput> workerBars, List<ForemanInput> foremen) {
        this(projectId, projectName, startDate, endDate, workerBars, foremen, Map.of());
    }
}
