package com.foremen.controller.model;

import java.math.BigDecimal;

import com.foremen.service.estimate.LabourPriceAdjustmentScope;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for the scoped LABOUR-price override endpoint
 * ({@code POST /api/estimates/project/{projectId}/labour-price-adjustment}, FOR-05-05 bug-report
 * Round 8 B).
 *
 * <p>Applies a signed percentage adjustment to the EFFECTIVE labour price of every in-scope estimate
 * line, writing each line's {@code unit_price_override = unit_price × (1 + pct/100)} and leaving the
 * frozen ORIGINAL {@code unit_price} untouched. A negative {@code pct} is a discount, a positive one
 * a surcharge, {@code 0} resets every in-scope line's override back to its original. Materials are
 * never affected. The write is DRAFT-gated and project-scoped (enforced in the service).
 *
 * @param scope    the adjustment scope — {@code PROJECT} (all lines of the estimate),
 *                 {@code WORK_CATEGORY} (lines whose work's category = {@code targetId}), or
 *                 {@code WORK_ITEM} (the single line for {@code targetId}); mandatory
 * @param targetId the scope target: the work-category id for {@code WORK_CATEGORY}, the work-item id
 *                 for {@code WORK_ITEM}, and ignored (may be {@code null}) for {@code PROJECT}
 * @param pct      the signed percentage adjustment applied to the original price (e.g. {@code -10}
 *                 for a 10% discount, {@code 15} for a 15% surcharge); mandatory
 */
public record LabourPriceAdjustmentRequest(
        @NotNull LabourPriceAdjustmentScope scope,
        Long targetId,
        @NotNull BigDecimal pct) {}
