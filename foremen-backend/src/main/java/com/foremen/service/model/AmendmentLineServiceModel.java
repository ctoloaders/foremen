package com.foremen.service.model;

import java.math.BigDecimal;

import com.foremen.dao.model.ChangeType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FOR-06-08 (Requirements 1.4, 3.1; design §Backend components) — the service-layer projection of a
 * single {@code AmendmentLine} within an {@code EstimateAmendment}. Mirrors the polymorphic
 * {@code AmendmentLineEntity} discriminated by {@link #changeType} (see {@link ChangeType} for which
 * fields each type populates) and carries, on the read path, the referenced work item / room / work
 * category localized display names resolved in the service mapper (ru → pl → code/label,
 * R11.1/R11.2).
 *
 * <p>Mutable ({@code @Data}) so the service mapper fills the resolved names after the FK ids are
 * mapped, mirroring the per-room projection on {@code WorkReportServiceModel}. Each line stores a
 * DELTA against the signed baseline, never an absolute value (R7.1).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AmendmentLineServiceModel {
    private Long id;

    private ChangeType changeType;

    private Long workItemId;
    /** Localized work-item display name (ru → pl → code); read path only. */
    private String workItemName;

    private Long roomId;
    /** Localized room display name (ru → pl → label/code); read path only. */
    private String roomName;

    private Long workCategoryId;
    /** Localized work-category display name (ru → pl → code); read path only. */
    private String workCategoryName;

    /** Signed volume delta (volume/additional-work); null for SCHEDULE_CHANGE. */
    private BigDecimal deltaQuantity;

    /** The new unit price (the Effective_Date_Seam field, R7.2); null for SCHEDULE_CHANGE. */
    private BigDecimal extraUnitPrice;

    /** Signed half-day schedule delta (SCHEDULE_CHANGE); null for cost lines. */
    private Integer scheduleDeltaHalfDays;

    /** Free text, untranslated. */
    private String description;
}
