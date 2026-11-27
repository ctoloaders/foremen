package com.foremen.service.estimateai;

import java.math.BigDecimal;
import java.util.List;

import com.foremen.service.estimate.LabourPriceAdjustmentScope;

/**
 * FOR-05-04 rework (Requirement 24.1; design R3) — one {@code assign_work} call: one work applied to one
 * room or to a listed set of rooms.
 *
 * @param workItemId   the catalog work
 * @param roomIds      the rooms of the project the work is applied to
 * @param volume       the model's volume for each listed room, nullable (the engine formula applies)
 * @param volumeReason why the model volume differs from the formula, nullable
 * @param rationale    why the work applies to the rooms, mandatory
 * @param status       {@code CONFIRMED} | {@code ASSUMED} | {@code QUESTION}
 * @param confidence   0..1, nullable
 * @param source       evidence, {@code "file.pdf, p.3"}, nullable
 * @param priceReason  explanation of a stated price coefficient, nullable
 * @param coefficients coefficients <b>stated by the user</b> that apply to this work; empty means no price edit
 */
public record AssignWorkCommand(
        Long workItemId,
        List<Long> roomIds,
        BigDecimal volume,
        String volumeReason,
        String rationale,
        String status,
        BigDecimal confidence,
        String source,
        String priceReason,
        List<Coefficient> coefficients) {

    public AssignWorkCommand {
        roomIds = roomIds == null ? List.of() : List.copyOf(roomIds);
        coefficients = coefficients == null ? List.of() : List.copyOf(coefficients);
    }

    /** A labour price coefficient stated by the user; {@code targetId} is {@code null} for {@code PROJECT}. */
    public record Coefficient(LabourPriceAdjustmentScope scope, Long targetId, BigDecimal pct) {
    }
}
