package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-path service model for a {@code ProjectSchedule} (FOR-05-10, Requirement 6), satisfying the
 * {@code ProjectScopedService}/{@code AdminService} CRUD contract that {@code ProjectScheduleService}
 * implements (entity-creation-rules step 4; design §ProjectScheduleService).
 *
 * <p>Following the FOR-05-08 {@code SignableDocumentServiceModel} convention, this is a flat model
 * that carries only the {@code projectId} FK and the optimistic-lock {@code version}. It exists only
 * to back the inherited generic CRUD surface; the rich client-reachable projection is the
 * {@code ScheduleView} the service assembles through its own
 * {@code getView}/{@code saveBars}/{@code autoCreate} methods, never through this flat model. No
 * field carries a man-days value or the internal {@code Daily_Output_Rate} (Requirement 9.3).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectScheduleServiceModel {
    private Long id;
    private Long projectId;
    private Long version;
}
