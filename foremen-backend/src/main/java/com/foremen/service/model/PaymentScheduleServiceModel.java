package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-path service model for a {@code PaymentSchedule} (FOR-06-07, Requirement 1), satisfying the
 * {@code ProjectScopedService}/{@code AdminService} CRUD contract that {@code PaymentScheduleService}
 * implements (entity-creation-rules step 4; design §PaymentScheduleService).
 *
 * <p>Following the FOR-05-10 {@code ProjectScheduleServiceModel} convention, this is a flat model
 * carrying only the {@code projectId} FK. It exists only to back the inherited generic CRUD surface
 * (and the auto project-filtered LIST read); the rich client-reachable projection is the
 * {@code PaymentScheduleView} the service assembles through its own
 * {@code create}/{@code generate}/{@code publish}/{@code rePlan}/{@code get} methods, never through
 * this flat model.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentScheduleServiceModel {
    private Long id;
    private Long projectId;
}
