package com.foremen.exception.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-05-05 additional task 3 — the HTTP 409 body of a room save that would change the estimate.
 * {@code code} is the stable machine key ({@code error.estimate.recalc.conflict}) the client uses to
 * recognize this conflict; every human-readable string is already localized to the request locale.
 *
 * @param code           always {@code error.estimate.recalc.conflict}
 * @param message        the localized top-level message
 * @param changes        the affected estimate cells
 * @param totalNetBefore the estimate net total before the save
 * @param totalNetAfter  the estimate net total after the save
 * @param offerImpact    FOR-05-07 — what approving does to the offer: {@code NONE} /
 *                       {@code WITHDRAW} / {@code REVISE} ({@code null} when unknown)
 */
public record EstimateRecalcConflictError(
        String code,
        String message,
        List<Change> changes,
        BigDecimal totalNetBefore,
        BigDecimal totalNetAfter,
        String offerImpact) {

    /** One affected cell with localized names. */
    public record Change(
            String kind,
            Long workItemId,
            String workName,
            String unitCode,
            Long roomId,
            String roomName,
            BigDecimal oldVolume,
            BigDecimal newVolume,
            BigDecimal oldLabour,
            BigDecimal newLabour,
            int materialsChanged) {
    }
}
