package com.foremen.service.procurement.approval;

/** FOR-11-02 (R5.5): counts for the tab badge. */
public record ApprovalSummary(long awaitingDecision, long overdue, long accepted, long clientOwn) {
}
