package com.foremen.service.model.mapper;

import com.foremen.dao.model.MatchTier;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.service.model.PurchaseLineServiceModel;

/**
 * FOR-06-04 (Requirements 4.1, 9.1; design §5.3) — the read-path projection from a reconciled
 * {@link ProcurementLineEntity} to its {@link PurchaseLineServiceModel} list row.
 *
 * <p>Pure static projection (no MapStruct, no I/O), mirroring the FOR-06-03
 * {@code ProcurementDocumentServiceMapper#projectLines} convention: it copies the line's verbatim
 * OCR fields + its source-document fields and derives the tier percent
 * ({@code EXACT}=100 / {@code MANUFACTURER_CATEGORY}=80 / {@code CATEGORY}=50 / {@code NONE}=0) from
 * the persisted {@link MatchTier}, reusing {@link ProcurementDocumentServiceMapper#tierPercent}.
 *
 * <p>The two <b>localized names</b> ({@code ru → pl → code}) are resolved by the caller (the
 * reconciliation service) — the matched-material name from the FOR-06-03
 * {@code PlannedMaterialCandidateProvider}, the unplanned-category name from the loaded dictionary
 * row — because the transactional entities carry no {@code nameRU}/{@code namePL} columns (R9.1).
 * The matched-material name is passed in; the unplanned-category name is resolved here from the
 * loaded {@link UnplannedExpenseCategoryEntity} via {@link #categoryName}.
 */
public final class PurchaseLineServiceMapper {

    private PurchaseLineServiceMapper() {
    }

    /**
     * Projects one reconciled line into its list row.
     *
     * @param line                the reconciled line (with its {@code document} and optional
     *                            {@code unplannedCategory} associations loaded)
     * @param matchedMaterialName the matched material's localized name ({@code ru → pl → code}),
     *                            resolved by the caller; {@code null} when the line is unmatched
     * @return the projected {@link PurchaseLineServiceModel}; never {@code null}
     */
    public static PurchaseLineServiceModel toServiceModel(ProcurementLineEntity line,
                                                          String matchedMaterialName) {
        ProcurementDocumentEntity document = line.getDocument();
        UnplannedExpenseCategoryEntity category = line.getUnplannedCategory();
        return new PurchaseLineServiceModel(
                line.getId(),
                document == null ? null : document.getId(),
                document == null ? null : document.getSupplierName(),
                document == null ? null : document.getUploadedAt(),
                line.getLineNo(),
                line.getRawName(),
                line.getQuantity(),
                line.getUnitHint(),
                line.getUnitPrice(),
                line.getLineTotal(),
                line.getManufacturerHint(),
                line.getMatchedMaterialId(),
                matchedMaterialName,
                line.getMatchTier(),
                ProcurementDocumentServiceMapper.tierPercent(line.getMatchTier()),
                line.getMatchStatus(),
                category == null ? null : category.getId(),
                categoryName(category));
    }

    /**
     * The unplanned-category localized name, resolved {@code ru → pl → code} (R9.1); {@code null}
     * when the line carries no classification.
     */
    public static String categoryName(UnplannedExpenseCategoryEntity category) {
        if (category == null) {
            return null;
        }
        return firstNonBlank(category.getNameRU(), category.getNamePL(), category.getCode());
    }

    /** First non-null, non-blank value among the candidates, or {@code null} when none qualifies. */
    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
