package com.foremen.service.procurement.matrix;

import java.math.BigDecimal;
import java.time.Instant;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;

/**
 * FOR-06-05 (design §Data Models, R4.2) — one linked purchase line in a planned material row's
 * per-material detail. It is a reconciled FOR-06-04 procurement line whose {@code matchedMaterialId}
 * points at the planned material and whose {@link MatchStatus} is {@code AUTO} or
 * {@code MANUAL_MATCHED} (a matched, not cleared, line).
 *
 * <p>The {@link #tierPercent()} (EXACT=100 / MANUFACTURER_CATEGORY=80 / CATEGORY=50 / NONE=0) is a
 * presentational label derived from the {@link #matchTier()} — it is shown for transparency and is
 * <strong>never</strong> a weight: a linked line contributes its full {@link #lineTotal()} to the
 * row's {@code Fact_Brutto} regardless of tier (R1.2). The {@link #unitMismatch()} flag marks a line
 * whose {@link #unitHint()} does not match the planned material's norm unit (R2.2) — such a line is
 * excluded from {@code Fact_Quantity} but kept in {@code Fact_Brutto}. Mirrors the frontend
 * {@code MatrixLinkedLine}.
 *
 * @param lineId           the procurement line id
 * @param documentId       the source procurement document id
 * @param supplierName     the source document's supplier name, or {@code null}
 * @param uploadedAt       the source document's upload time, or {@code null}
 * @param rawName          the OCR raw line name
 * @param manufacturerHint the OCR manufacturer hint, or {@code null}
 * @param quantity         the OCR quantity, or {@code null}
 * @param unitHint         the OCR unit hint, or {@code null}
 * @param matchTier        the confidence tier (display only, not a weight)
 * @param tierPercent      the derived tier percent (100 / 80 / 50 / 0), display only
 * @param matchStatus      the match provenance ({@code AUTO} / {@code MANUAL_MATCHED})
 * @param lineTotal        the line money total ({@code Fact_Brutto} contribution), or {@code null}
 * @param unitMismatch     whether this line's unit does not match the planned norm unit (R2.2)
 */
public record MatrixLinkedLineDto(
        Long lineId,
        Long documentId,
        String supplierName,
        Instant uploadedAt,
        String rawName,
        String manufacturerHint,
        BigDecimal quantity,
        String unitHint,
        MatchTier matchTier,
        int tierPercent,
        MatchStatus matchStatus,
        BigDecimal lineTotal,
        boolean unitMismatch) {
}
