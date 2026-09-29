package com.foremen.service.estimate.materials;

import java.math.BigDecimal;
import java.util.List;

/**
 * The reserve-map write request body for the Materials tab (FOR-05-05b, design §B4,
 * {@code PUT /project/{projectId}/materials/reserve}). It carries the <b>client-expanded</b>
 * per-material reserve list: the frontend mass-apply convenience (R4.4) is expanded client-side into
 * one entry per affected material before the request is sent, so there is <b>no branch-level stored
 * value</b> — the map is per-material only (R4.4).
 *
 * <p>Every {@link ReserveEntry} is server-validated on write (percent in {@code [0..100]}, ≤2
 * decimals); any invalid or malformed entry rejects the <b>whole</b> write with a localized message
 * (R9.4). A {@code null}/empty percent is treated as <em>unset</em> (identity, no reserve) rather than
 * an error (R9.3). Mirrors the frontend {@code MaterialsReserveRequest}.
 *
 * @param entries the client-expanded per-material reserve entries; {@code null} ⇒ an empty map
 */
public record MaterialsReserveRequest(List<ReserveEntry> entries) {

    /**
     * One material's requested reserve percent (R4.1, R4.4).
     *
     * @param materialId the concrete material id the reserve applies to
     * @param percent    the requested reserve percent (0..100, ≤2 decimals); {@code null}/empty ⇒
     *                   unset (identity, R9.3)
     */
    public record ReserveEntry(Long materialId, BigDecimal percent) {
    }
}
