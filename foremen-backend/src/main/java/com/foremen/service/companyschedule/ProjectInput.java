package com.foremen.service.companyschedule;

import java.time.LocalDate;
import java.util.List;

/**
 * DB-free input to the pure {@code CrossProjectScheduleProjector} describing one Accessible_Project
 * and the raw material needed to project it (FOR-07-01 Requirements 1.2, 1.3, 2.1; design
 * §"Pure projector").
 *
 * <p>The service loads a project's schedule, members, and project row through existing DAOs and
 * flattens them into this plain record so the projector has no database or Spring dependency and is
 * directly unit- and property-testable (mirrors {@code ScheduleCalculator}).
 *
 * <p>Only <strong>Real_Bars</strong> (a {@code project_schedule_bars} row whose {@code projectMember}
 * is non-null) become {@link WorkerBarInput}s; {@code Uncovered_Bar}s (null-member) are excluded
 * upstream and never reach the projector (Requirement 2.1). {@link #foremen()} holds only the ACTIVE
 * FOREMAN members of this project, from which the projector synthesizes a Foreman_Span_Bar.
 *
 * @param projectId   the owning project id
 * @param projectName the owning project name
 * @param startDate   the project's Schedule_Anchor (nullable; {@code null} = Unanchored_Project)
 * @param endDate     the project's end date (nullable; a Foreman_Span_Bar is datable only when both
 *                    {@code startDate} and {@code endDate} are present)
 * @param workerBars  this project's Real_Bars (non-null member), each already resolved to its worker
 *                    and work category
 * @param foremen     this project's ACTIVE FOREMAN members (source of Foreman_Span_Bars)
 */
public record ProjectInput(long projectId, String projectName,
                           LocalDate startDate, LocalDate endDate,
                           List<WorkerBarInput> workerBars,
                           List<ForemanInput> foremen) {
}
