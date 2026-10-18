package com.foremen.controller.model.schedule;

import java.math.BigDecimal;

/**
 * FOR-06-02 (Requirement 1.4) — the project-level plan/actual roll-up across every category: the
 * summed planned / confirmed / pending quantity, the planned-quantity-weighted project confirmed
 * completion, the count of categories in each trajectory status, and the overall projected finish
 * (the latest category projected finish).
 *
 * @param plannedQuantity     the project's total planned quantity
 * @param confirmedQuantity   the project's total APPROVED reported quantity
 * @param pendingQuantity     the project's total SUBMITTED + CHANGES_REQUESTED reported quantity
 * @param confirmedCompletion the planned-quantity-weighted project confirmed completion in {@code [0, 1]}
 * @param onTrack             the number of categories classified {@code ON_TRACK}
 * @param atRisk              the number of categories classified {@code AT_RISK}
 * @param overdue             the number of categories classified {@code OVERDUE}
 * @param projectedFinishDay  the latest category projected finish day; {@code null} when none projects
 */
public record PlanActualTotals(
        BigDecimal plannedQuantity,
        BigDecimal confirmedQuantity,
        BigDecimal pendingQuantity,
        double confirmedCompletion,
        int onTrack,
        int atRisk,
        int overdue,
        Double projectedFinishDay
) {}
