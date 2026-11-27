package com.foremen.service.estimateai;

import java.math.BigDecimal;

/** FOR-05-04: the engine facts the job needs per cell (formula volume, category); supplied by the cell facts resolver. */
public interface EngineFacts {

    /** The volume the engine's formula/unit fallback gives for the cell, or {@code null} when unknown. */
    BigDecimal formulaVolume(Long workItemId, Long roomId);

    /** The work category id of the work item, or {@code null} when unknown. */
    Long categoryId(Long workItemId);
}
