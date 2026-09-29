package com.foremen.service.estimate.materials;

import com.foremen.dao.model.ConsumptionBranch;

/**
 * One dashboard summary of the Materials tab (FOR-05-05b, design "Read model DTOs"): the material
 * consumption money totals for a branch (or, when {@link #branch()} is {@code null}, the project-wide
 * grand total), presented for BOTH the as-is figures and the reserve/roundup-applied (effective)
 * figures, each as a net + brutto pair (R6.2, R6.3). Null-priced rows contribute nothing (R12.4).
 * Mirrors the frontend {@code BranchDashboard}.
 *
 * @param branch        the branch this summary covers, or {@code null} for the project-wide grand total
 * @param asIsTotal     the summed money using each row's as-is quantity (R6.2)
 * @param effectiveTotal the summed money using each row's effective quantity (R6.2, R6.4)
 */
public record BranchDashboardDto(
        ConsumptionBranch branch,
        MoneyBrutto asIsTotal,
        MoneyBrutto effectiveTotal) {
}
