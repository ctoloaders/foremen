package com.foremen.service.procurement.matrix;

import java.math.BigDecimal;
import java.time.Instant;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;

/**
 * FOR-06-05 (design §B1) — the pure fact-side input to the {@link MaterialsMatrixAssembler}: one
 * FOR-06-04 reconciled procurement line, already resolved by {@code MaterialsMatrixService}
 * (task 2.1) with its source-document fields filled.
 *
 * <p>It is an immutable value with no I/O so the assembler stays pure and property-testable without
 * Spring. A line is a <strong>fact contribution</strong> to a planned material row iff its
 * {@link #matchedMaterialId()} equals that material and its {@link #matchStatus()} is {@code AUTO} or
 * {@code MANUAL_MATCHED} (a matched, not cleared, line); the assembler drops every other line up
 * front (R1.6). The {@link #matchTier()} is carried for the detail only and never scales the line's
 * contribution (R1.2).
 *
 * @param lineId             the procurement line id
 * @param matchedMaterialId  the soft FK to the matched concrete material, or {@code null} (unmatched)
 * @param matchStatus        the match provenance; only {@code AUTO} / {@code MANUAL_MATCHED} count
 * @param matchTier          the confidence tier (display only, not a weight)
 * @param quantity           the OCR quantity, or {@code null}
 * @param unitHint           the OCR unit hint, or {@code null}
 * @param lineTotal          the line money total ({@code Fact_Brutto} contribution), or {@code null}
 * @param documentId         the source procurement document id
 * @param supplierName       the source document's supplier name, or {@code null}
 * @param uploadedAt         the source document's upload time, or {@code null}
 * @param rawName            the OCR raw line name
 * @param manufacturerHint   the OCR manufacturer hint, or {@code null}
 */
public record MatrixFactLine(
        Long lineId,
        Long matchedMaterialId,
        MatchStatus matchStatus,
        MatchTier matchTier,
        BigDecimal quantity,
        String unitHint,
        BigDecimal lineTotal,
        Long documentId,
        String supplierName,
        Instant uploadedAt,
        String rawName,
        String manufacturerHint) {
}
