package com.foremen.dao.model;

/**
 * FOR-06-03 (Requirements 4.3, 5) — the confidence tier of a {@link ProcurementLineEntity}'s
 * proposed material match (design §4.3). Stored on {@code procurement_lines.match_tier}
 * (VARCHAR(32), NOT NULL) as the enum name. The pure {@code MatchEngine} assigns the
 * <strong>highest reachable</strong> tier for each extracted line.
 *
 * <ul>
 *   <li>{@code EXACT} — manufacturer + category + name all match (100 %, R5.2).</li>
 *   <li>{@code MANUFACTURER_CATEGORY} — manufacturer + category match (80 %, R5.3).</li>
 *   <li>{@code CATEGORY} — category only (50 %, R5.4).</li>
 *   <li>{@code NONE} — no candidate matches at any tier (0 %, R5.5).</li>
 * </ul>
 *
 * <p>The {@code tier → percent} map (EXACT=100, MANUFACTURER_CATEGORY=80, CATEGORY=50, NONE=0) is a
 * presentational label derived from the tier, not a persisted free continuous score (R5.8).
 */
public enum MatchTier {
    EXACT,
    MANUFACTURER_CATEGORY,
    CATEGORY,
    NONE
}
