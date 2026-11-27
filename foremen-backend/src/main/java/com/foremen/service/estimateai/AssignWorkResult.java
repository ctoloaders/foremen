package com.foremen.service.estimateai;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-05-04 rework (design R3) — what one committed {@code assign_work} call wrote.
 *
 * @param workItemId the assigned work
 * @param workCode   its catalog code
 * @param cells      one entry per room, in the command's order
 */
public record AssignWorkResult(Long workItemId, String workCode, List<Cell> cells) {

    /**
     * @param created          {@code true} when the cell did not exist before the call
     * @param volumeOverridden {@code true} when the final volume is the model's manual override
     * @param materialLines    number of material lines on the cell after the call
     */
    public record Cell(Long roomId, Long roomQtyId, boolean created, BigDecimal volume,
                       boolean volumeOverridden, int materialLines) {
    }

    public int materialLines() {
        return cells.stream().mapToInt(Cell::materialLines).sum();
    }
}
