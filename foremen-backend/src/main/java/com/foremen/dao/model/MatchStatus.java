package com.foremen.dao.model;

/**
 * FOR-06-03 (Requirements 4.3, 5.5) — the match status of a {@link ProcurementLineEntity}
 * (design §4.3). Stored on {@code procurement_lines.match_status} (VARCHAR(16), NOT NULL) as the
 * enum name.
 *
 * <ul>
 *   <li>{@code AUTO} — set by the {@code MatchEngine} at a non-{@code NONE} tier.</li>
 *   <li>{@code UNMATCHED} — tier {@code NONE}; no proposed material.</li>
 * </ul>
 *
 * <p>The manual-override status (a user re-link) lives in the sibling FOR-06-04, not here: this spec
 * owns intake + automatic matching only.
 */
public enum MatchStatus {
    AUTO,
    UNMATCHED
}
