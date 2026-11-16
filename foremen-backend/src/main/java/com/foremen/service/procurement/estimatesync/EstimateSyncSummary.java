package com.foremen.service.procurement.estimatesync;

/**
 * FOR-11-06 (R5.6): result of one estimate sync run. The four counters are mutually exclusive per
 * estimate-derived position: {@code created} (new position), {@code updated} (PLANNED position whose
 * quantity, unit, prices or dates were refreshed), {@code flagged} (position carrying
 * {@code estimate_changed} or {@code estimate_removed} after the run) and {@code unchanged}.
 */
public record EstimateSyncSummary(int created, int updated, int unchanged, int flagged) {
}
