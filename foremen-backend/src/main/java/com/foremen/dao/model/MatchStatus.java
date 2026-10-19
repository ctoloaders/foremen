package com.foremen.dao.model;

/**
 * FOR-06-03 (Requirements 4.3, 5.5) — the match status of a {@link ProcurementLineEntity}
 * (design §4.3), broadened by FOR-06-04 (Requirement 1.2, design §4.2) with the two manual states.
 * Stored on {@code procurement_lines.match_status} (VARCHAR(16), NOT NULL) as the enum name — adding
 * values needs no schema change; existing rows keep their {@code AUTO}/{@code UNMATCHED} value.
 *
 * <ul>
 *   <li>{@code AUTO} — FOR-06-03: set by the {@code MatchEngine} at a non-{@code NONE} tier.</li>
 *   <li>{@code UNMATCHED} — FOR-06-03: tier {@code NONE}; no proposed material.</li>
 *   <li>{@code MANUAL_MATCHED} — FOR-06-04: a user re-linked the line to a chosen planned material.</li>
 *   <li>{@code MANUAL_UNMATCHED} — FOR-06-04: a user deliberately cleared the match (may carry an
 *       unplanned-expense category).</li>
 * </ul>
 */
public enum MatchStatus {
    AUTO,
    UNMATCHED,
    MANUAL_MATCHED,
    MANUAL_UNMATCHED
}
