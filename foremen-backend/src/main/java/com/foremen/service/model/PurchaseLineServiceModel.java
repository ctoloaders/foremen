package com.foremen.service.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;

/**
 * FOR-06-04 (Requirements 4.1, 9.1; design §5.3) — read-path service model for one reconciled
 * procurement line, the row of the project-wide reconciled purchases list ({@code list(projectId,
 * filters)}).
 *
 * <p>Each row carries its <b>source document</b> ({@code documentId}, {@code supplierName},
 * {@code uploadedAt}), the verbatim OCR fields ({@code lineNo}, {@code rawName}, {@code quantity},
 * {@code unitHint}, {@code unitPrice}, {@code lineTotal}, {@code manufacturerHint}), the resolved
 * matched-material <b>localized name</b> ({@code ru → pl → code}, filled by the service from the
 * FOR-06-03 {@code PlannedMaterialCandidateProvider}) or {@code null} when unmatched, the
 * {@link MatchTier}, the <b>derived tier percent</b> ({@code EXACT}=100 /
 * {@code MANUFACTURER_CATEGORY}=80 / {@code CATEGORY}=50 / {@code NONE}=0), the {@link MatchStatus},
 * and the {@code unplannedCategory} <b>localized name</b> ({@code ru → pl → code}) or {@code null}.
 *
 * <p>The transactional entities carry no {@code nameRU}/{@code namePL} columns (R9.1) — both display
 * names are resolved at read time from the backing material / dictionary entities, not persisted on
 * the line.
 *
 * @param lineId               the reconciled line's id
 * @param documentId           the source {@code ProcurementDocument} id
 * @param supplierName         the source document's parse-derived supplier/store name (nullable)
 * @param uploadedAt           the source document's upload timestamp
 * @param lineNo               the sequential 1-based position within the document
 * @param rawName              the verbatim OCR product text
 * @param quantity             the parsed quantity (nullable, {@code >= 0})
 * @param unitHint             the verbatim OCR unit text (nullable)
 * @param unitPrice            the parsed per-unit price (nullable, {@code >= 0})
 * @param lineTotal            the parsed line total (nullable, {@code >= 0})
 * @param manufacturerHint     the verbatim OCR manufacturer/brand hint (nullable)
 * @param matchedMaterialId    the matched {@code Concrete_Material} id (null when unmatched)
 * @param matchedMaterialName  the matched material's localized name, {@code ru → pl → code} (null when unmatched)
 * @param matchTier            the confidence tier
 * @param matchPercent         the derived tier percent (100 / 80 / 50 / 0)
 * @param matchStatus          the match provenance (auto / unmatched / manual-matched / manual-unmatched)
 * @param unplannedCategoryId  the classified {@code UnplannedExpenseCategory} id (null when unset)
 * @param unplannedCategoryName the unplanned-category localized name, {@code ru → pl → code} (null when unset)
 */
public record PurchaseLineServiceModel(
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
