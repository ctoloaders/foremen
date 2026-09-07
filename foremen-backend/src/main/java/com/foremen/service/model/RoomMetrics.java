package com.foremen.service.model;

import java.math.BigDecimal;

/**
 * Immutable result of the {@link com.foremen.service.RoomCalculationService} geometry calculation
 * engine (FOR-04-14). Carries the metrics derived from a {@code RoomGeometry}, each rounded to
 * {@code NUMERIC(12,2)} with {@code HALF_UP}:
 *
 * <ul>
 *   <li>{@code floorArea} (m²) — absolute shoelace area of the polygon (Req 3.1).</li>
 *   <li>{@code perimeter} (mb) — sum of Euclidean wall-edge lengths (Req 3.2).</li>
 *   <li>{@code wallArea} (m²) — {@code max(0, perimeter × ceilingHeight − doorArea − windowArea −
 *       wallGap × ceilingHeight)} (Req 3.3).</li>
 *   <li>{@code doorArea}/{@code windowArea} (m²) — Σ {@code count × height × width} per opening
 *       type (Req 3.4).</li>
 *   <li>{@code doorCount}/{@code windowCount} (szt) — Σ counts per opening type (Req 3.4).</li>
 *   <li>{@code wallGap}/{@code finishGap} (mb) — Σ per-wall gap lengths (Req 2.5).</li>
 * </ul>
 *
 * @param floorArea   floor area (m²), source {@code CALCULATED}
 * @param perimeter   perimeter (mb), source {@code CALCULATED}
 * @param wallArea    wall area (m²), floored at zero, source {@code CALCULATED}
 * @param doorArea    total door area (m²), source {@code CALCULATED}
 * @param windowArea  total window area (m²), source {@code CALCULATED}
 * @param doorCount   total number of door openings (szt)
 * @param windowCount total number of window openings (szt)
 * @param wallGap     summed "missing wall" length across walls (mb)
 * @param finishGap   summed "missing finish" length across walls (mb)
 */
public record RoomMetrics(
        BigDecimal floorArea,
        BigDecimal perimeter,
        BigDecimal wallArea,
        BigDecimal doorArea,
        BigDecimal windowArea,
        int doorCount,
        int windowCount,
        BigDecimal wallGap,
        BigDecimal finishGap) {
}
