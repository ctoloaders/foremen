package com.foremen.dao.model;

/**
 * FOR-06-01 (Requirements 1.1, 4.1) — the server-persisted lifecycle status of a
 * {@link WorkReportEntity} (design §3.3). There is <strong>no</strong> server-side {@code DRAFT}:
 * {@code DRAFT} is a client-only localStorage state (R7), so a report first exists server-side at
 * {@code SUBMITTED} (a non-FOREMAN author submits) or directly at {@code APPROVED} (the FOREMAN
 * self-report fast path, R5).
 *
 * <p>Allowed transitions (enforced at the service layer):
 * {@code SUBMITTED → APPROVED | REJECTED | CHANGES_REQUESTED};
 * {@code CHANGES_REQUESTED → SUBMITTED (resubmit) | APPROVED}. {@code APPROVED} and {@code REJECTED}
 * are terminal. Stored on {@code work_reports.status} (VARCHAR(32), NOT NULL) as the enum name.
 */
public enum WorkReportStatus {
    SUBMITTED,
    APPROVED,
    REJECTED,
    CHANGES_REQUESTED
}
