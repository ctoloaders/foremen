package com.foremen.service.model;

import java.time.LocalDate;

import com.foremen.dao.model.AmendmentStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FOR-06-08 (Requirement 1.1, 1.2; design §Backend components) — the service-layer list-row read
 * model for an {@code EstimateAmendment}, satisfying the {@code ProjectScopedService}/
 * {@code AdminService} CRUD contract {@code AmendmentService} implements (entity-creation-rules
 * step 4).
 *
 * <p>Carries the per-row summary the amendments list renders (R1.1): the id, the owning
 * {@code projectId}, the {@code seqNo}, the {@code status} ({@code Amendment_Status}, labeled on the
 * frontend via the {@code amendments.status.*} keys — no backend i18n, R11.1), the number of change
 * lines ({@code lineCount}), the {@code effectiveDate} (unset in this spec; set only by FOR-06-09,
 * R7.4), and the free-text {@code note}. The full per-line split lives on
 * {@link AmendmentServiceExtendedModel}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AmendmentServiceModel {
    private Long id;

    private Long projectId;

    private Integer seqNo;

    private AmendmentStatus status;

    /** Number of change lines on the amendment (R1.1). */
    private Integer lineCount;

    /** Unset in this spec; set only by FOR-06-09 on/after signing (R7.4). */
    private LocalDate effectiveDate;

    /** Free text, untranslated (R11.3). */
    private String note;
}
