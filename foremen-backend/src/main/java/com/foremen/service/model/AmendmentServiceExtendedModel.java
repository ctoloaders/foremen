package com.foremen.service.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.foremen.dao.model.AmendmentStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FOR-06-08 (Requirements 1.4, 3.1, 4.3; design §Backend components) — the write-path + single-read
 * service model for an {@code EstimateAmendment}, satisfying the {@code ProjectScopedService}/
 * {@code AdminService} CRUD contract {@code AmendmentService} implements (entity-creation-rules
 * step 4).
 *
 * <p><b>Write path.</b> Creation carries only the owning {@code projectId}; the {@code seqNo},
 * {@code status} ({@code OPEN}), the empty line set, and the eligibility / single-open guards are all
 * derived/enforced server-side by {@code AmendmentService} (R2.2, R2.5). Change lines are NOT added
 * through this aggregate create — they go through the dedicated {@code addLine} path (an
 * {@code AddLineRequest}) so the same-subject merge can be resolved (R3, R5); {@code effectiveDate}
 * and {@code signableDocument} are written only by FOR-06-09 (R7.4), so they are absent here.
 *
 * <p><b>Read path.</b> The extended model is the single-read projection too: the service mapper fills
 * {@code seqNo}/{@code status}/{@code effectiveDate}/{@code note}, projects the owned change lines
 * into {@link #lines} (each with its resolved localized names), and computes the informational
 * aggregate {@link #totalAddedValue} = {@code Σ deltaQuantity × extraUnitPrice} over the cost-bearing
 * lines (R4.3) — an authoring-UI figure that is NOT applied to any downstream data in this spec.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AmendmentServiceExtendedModel {
    private Long id;

    private Long projectId;

    // --- Read-side projection (FOR-06-08 detail view) ---
    //
    // On the WRITE path (create) only projectId is supplied; the service derives seqNo/status and
    // starts with an empty line set, so the fields below stay null/empty. On the READ path the
    // service mapper populates them so GET /{id} returns the status, the effective date, the change
    // lines, and the informational aggregate.

    private Integer seqNo;

    private AmendmentStatus status;

    /** Unset in this spec; set only by FOR-06-09 on/after signing (R7.4). */
    private LocalDate effectiveDate;

    /** Free text, untranslated (R11.3). */
    private String note;

    /** The amendment's change lines (R1.4); read path only. */
    private List<AmendmentLineServiceModel> lines = new ArrayList<>();

    /**
     * Informational aggregate {@code Σ deltaQuantity × extraUnitPrice} over the cost-bearing lines
     * (R4.3); read path only. NOT applied to any downstream data in this spec.
     */
    private BigDecimal totalAddedValue;
}
