package com.foremen.service.estimate.recalc;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-05-05 additional task 2 — the change-impact of a room save on the project's estimate: every
 * estimate cell that would be added, removed or changed by the automatic re-derivation, plus the
 * estimate net total before and after. Computed inside the room-save transaction by diffing a
 * snapshot taken before the room writes against one taken after the derivation, so it reflects the
 * exact numbers the force path persists.
 *
 * @param changes        the affected cells (empty ⇒ the save does not change the estimate)
 * @param totalNetBefore the estimate net total before the save
 * @param totalNetAfter  the estimate net total after the save's derivation
 */
public record EstimateImpact(List<Change> changes, BigDecimal totalNetBefore, BigDecimal totalNetAfter) {

    /** An empty impact (no estimate, or nothing changed). */
    public static EstimateImpact none(BigDecimal totalNet) {
        return new EstimateImpact(List.of(), totalNet, totalNet);
    }

    public boolean isEmpty() {
        return changes == null || changes.isEmpty();
    }

    /** How a cell is affected. */
    public enum Kind {
        /** A new cell (a work applied to a new room from a same-type sibling). */
        ADDED,
        /** A cell removed because its room is deleted. */
        REMOVED,
        /** An existing cell whose Volume / labour / material quantities change. */
        CHANGED
    }

    /**
     * One affected estimate cell. Names are carried in both languages and localized by the
     * exception handler to the request locale.
     *
     * @param kind             added / removed / changed
     * @param workItemId       the work of the cell
     * @param workNameRu       the work name (RU)
     * @param workNamePl       the work name (PL)
     * @param unitCode         the work's unit code (e.g. {@code m2}), or {@code null}
     * @param roomId           the room of the cell ({@code null} for a not-yet-persisted new room)
     * @param roomLabel        the room label, or the localized room-type name when unlabeled (RU)
     * @param roomLabelPl      the room label, or the localized room-type name when unlabeled (PL)
     * @param oldVolume        the Volume before ({@code null} for ADDED)
     * @param newVolume        the Volume after ({@code null} for REMOVED)
     * @param oldLabour        the labour net (Volume × unit price) before ({@code null} for ADDED)
     * @param newLabour        the labour net after ({@code null} for REMOVED)
     * @param materialsChanged the number of the cell's material lines whose quantity changes
     */
    public record Change(
            Kind kind,
            Long workItemId,
            String workNameRu,
            String workNamePl,
            String unitCode,
            Long roomId,
            String roomLabel,
            String roomLabelPl,
            BigDecimal oldVolume,
            BigDecimal newVolume,
            BigDecimal oldLabour,
            BigDecimal newLabour,
            int materialsChanged) {
    }
}
