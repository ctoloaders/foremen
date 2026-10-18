package com.foremen.controller.model.schedule;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * FOR-06-02 (Requirement 6.5) — the optional revenue roll-up of the plan/actual overlay, present
 * only for a Money_Viewer (resolved exactly as the planning Gantt's {@code ScheduleView} gates its
 * money fields). For a non-money-viewer the owning service leaves the whole {@link PlanActualMoney}
 * {@code null} and the {@link PlanActualView}'s {@link JsonInclude}({@code NON_NULL}) omits it.
 *
 * <p>Figures are read-only aggregations over the frozen estimate line unit prices (FOR-05); this
 * spec computes no cost, margin, or payroll (those are FOR-06-06 / FOR-06-10 / FOR-06-11).
 *
 * @param currency        the estimate currency code
 * @param plannedValue    the planned revenue: {@code Σ unitPrice × plannedQuantity}
 * @param confirmedValue  the confirmed revenue: {@code Σ unitPrice × confirmedQuantity}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanActualMoney(
        String currency,
        BigDecimal plannedValue,
        BigDecimal confirmedValue
) {}
