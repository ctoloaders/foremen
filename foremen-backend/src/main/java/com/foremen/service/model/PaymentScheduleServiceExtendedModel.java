package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Write-path service model for a {@code PaymentSchedule} (FOR-06-07, Requirement 1), satisfying the
 * {@code ProjectScopedService}/{@code AdminService} CRUD contract that {@code PaymentScheduleService}
 * implements (entity-creation-rules step 4; design §PaymentScheduleService).
 *
 * <p>Following the FOR-05-10 {@code ProjectScheduleServiceExtendedModel} convention, this is a flat
 * model carrying the {@code projectId} FK. The schedule lifecycle itself (create, generate, publish,
 * re-plan, read) is driven through {@code PaymentScheduleService}'s dedicated methods — which seed
 * {@code basisGross}/{@code currency} from the project's APPROVED offer and return a rich
 * {@code PaymentScheduleView} — not through raw CRUD writes; this model exists only to satisfy the
 * generic CRUD contract.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentScheduleServiceExtendedModel {
    private Long id;
    private Long projectId;
}
