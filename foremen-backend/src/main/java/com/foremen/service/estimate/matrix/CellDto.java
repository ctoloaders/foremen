package com.foremen.service.estimate.matrix;

import java.math.BigDecimal;
import java.util.List;

/**
 * A single work &times; room intersection of the Estimate tab matrix (FOR-05-05, design §B6). When
 * {@code assigned} is {@code false} the cell renders empty/assignable and the pricing fields are the
 * neutral defaults (R1.3). {@code volume} is the single Volume shared by labour and every material
 * (R4.1); {@code formulaUsed} is the source formula text ({@code null} when the unit fallback was
 * used) and {@code fallbackUsed} flags the R5.3 disclosure. {@code costRange} collapses
 * ({@code min == max}) when every material line is concrete (R6.4, R7.2). Mirrors the frontend
 * {@code CellDto}.
 *
 * <p>{@code volumeOverridden} (FOR-05-05 #7) is {@code true} iff the displayed {@code volume} is a
 * MANUAL override the user set for this cell (rather than the formula-resolved Volume) — the frontend
 * shows an override indicator from it. When overridden, {@code volume} is the stored manual quantity
 * (not the re-derived formula value), while {@code formulaUsed}/{@code fallbackUsed} still describe the
 * formula that would otherwise apply.
 *
 * @param workItemId  the work (row) id
 * @param roomId      the room (column) id
 * @param assigned    whether the work is assigned in this room
 * @param volume      the single resolved Volume for the cell (R4.1)
 * @param formulaUsed the applicable formula's source text, or {@code null} when the fallback was used
 * @param formulaKey  a stable formula identifier the frontend maps to a localized label (#2): the
 *                    default formula's {@code sourceText} (e.g. {@code "floorArea"}), or
 *                    {@code "fallback"} when the unit→dimension fallback supplied the Volume, or
 *                    {@code null} when neither applies. {@code formulaUsed} stays the raw fallback text
 * @param fallbackUsed {@code true} iff the Volume came from the unit→dimension fallback (R5.3)
 * @param volumeOverridden {@code true} iff {@code volume} is a manual override, not the formula (#7)
 * @param labour      the labour contribution {@code unitPrice × Volume} (a point)
 * @param materials   the cell's copied material lines
 * @param costRange   the cell cost band {@code labour + Σ material ranges}; collapses when concrete
 * @param fillState   the cell's fill state (R8.1)
 */
public record CellDto(
        Long workItemId,
        Long roomId,
        boolean assigned,
        BigDecimal volume,
        String formulaUsed,
        String formulaKey,
        boolean fallbackUsed,
        boolean volumeOverridden,
        BigDecimal labour,
        List<MaterialLineDto> materials,
        MoneyRange costRange,
        FillState fillState) {

    /** An unassigned cell for {@code (workItemId, roomId)} — empty/assignable neutral defaults (R1.3). */
    public static CellDto unassigned(Long workItemId, Long roomId) {
        return new CellDto(
                workItemId,
                roomId,
                false,
                BigDecimal.ZERO,
                null,
                null,
                false,
                false,
                BigDecimal.ZERO,
                List.of(),
                MoneyRange.ZERO,
                FillState.placeholder);
    }
}
