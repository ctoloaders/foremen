package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Write-path service model for a {@code ProjectSchedule} (FOR-05-10, Requirement 6), satisfying the
 * {@code ProjectScopedService}/{@code AdminService} CRUD contract that {@code ProjectScheduleService}
 * implements (entity-creation-rules step 4; design §ProjectScheduleService).
 *
 * <p>Following the FOR-05-08 {@code SignableDocumentServiceExtendedModel} convention, this is a flat
 * model carrying the {@code projectId} FK and the optimistic-lock {@code version}. The schedule
 * lifecycle itself (read, save-bars, auto-create, readiness) is driven through
 * {@code ProjectScheduleService}'s dedicated methods, not raw CRUD writes; this model exists only to
 * satisfy the generic CRUD contract. No field carries a man-days value or the internal
 * {@code Daily_Output_Rate} (Requirement 9.3).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectScheduleServiceExtendedModel {
    private Long id;
    private Long projectId;
    private Long version;
}
