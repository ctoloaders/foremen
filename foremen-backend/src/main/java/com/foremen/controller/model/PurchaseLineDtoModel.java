package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;

/**
 * FOR-06-04 (Requirements 4.1, 9.1; design §5.3) — the row DTO of the project-wide reconciled
 * purchases list ({@code GET /api/procurement-lines}). One row per reconciled
 * {@code ProcurementLine} across the project's documents.
 *
 * <p>Carries the <b>source document</b> ({@code documentId}, {@code supplierName},
 * {@code uploadedAt}), the verbatim OCR fields ({@code lineNo}, {@code rawName}, {@code quantity},
 * {@code unitHint}, {@code unitPrice}, {@code lineTotal}, {@code manufacturerHint}), the matched-
 * material <b>localized name</b> ({@code ru → pl → code}, resolved by the service) or {@code null},
 * the {@link MatchTier}, the derived tier percent (100 / 80 / 50 / 0), the {@link MatchStatus}, and
 * the {@code unplannedCategory} <b>localized name</b> ({@code ru → pl → code}) or {@code null}. The
 * transactional entities carry no {@code nameRU}/{@code namePL} columns (R9.1).
 *
 * @param lineId                the reconciled line's id
 * @param documentId            the source {@code ProcurementDocument} id
 * @param supplierName          the source document's supplier/store name (nullable)
 * @param uploadedAt            the source document's upload timestamp
 * @param lineNo                the sequential 1-based position within the document
 * @param rawName               the verbatim OCR product text
 * @param quantity              the parsed quantity (nullable, {@code >= 0})
 * @param unitHint              the verbatim OCR unit text (nullable)
 * @param unitPrice             the parsed per-unit price (nullable, {@code >= 0})
 * @param lineTotal             the parsed line total (nullable, {@code >= 0})
 * @param manufacturerHint      the verbatim OCR manufacturer/brand hint (nullable)
 * @param matchedMaterialId     the matched {@code Concrete_Material} id (null when unmatched)
 * @param matchedMaterialName   the matched material's localized name, {@code ru → pl → code} (null when unmatched)
 * @param matchTier             the confidence tier
 * @param matchPercent          the derived tier percent (100 / 80 / 50 / 0)
 * @param matchStatus           the match provenance
 * @param unplannedCategoryId   the classified {@code UnplannedExpenseCategory} id (null when unset)
 * @param unplannedCategoryName the unplanned-category localized name, {@code ru → pl → code} (null when unset)
 */
public record PurchaseLineDtoModel(
        Long lineId,
        Long documentId,
        String supplierName,
        OffsetDateTime uploadedAt,
        int lineNo,
        String rawName,
        BigDecimal quantity,
        String unitHint,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String manufacturerHint,
        Long matchedMaterialId,
        String matchedMaterialName,
        MatchTier matchTier,
        int matchPercent,
        MatchStatus matchStatus,
        Long unplannedCategoryId,
        String unplannedCategoryName) {
}
