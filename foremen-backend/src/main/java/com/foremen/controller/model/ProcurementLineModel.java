package com.foremen.controller.model;

import java.math.BigDecimal;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;

/**
 * FOR-06-03 (Requirements 5.8, 10.1; design §5.6) — the per-line row sub-DTO of a procurement
 * document, carried back on the read projection of a {@code PARSED} document. One row per extracted
 * purchased item ({@code ProcurementLineEntity}), ordered by {@code lineNo}.
 *
 * <p>Carries the verbatim OCR fields ({@code rawName}, {@code quantity}, {@code unitHint},
 * {@code unitPrice}, {@code lineTotal}, {@code manufacturerHint}) plus the pure {@code MatchEngine}
 * proposal: the matched-material id, its <b>localized display name</b> ({@code ru → pl → code},
 * resolved from the FOR-05-05b candidate projection — the transactional entities carry no
 * {@code nameRU}/{@code namePL} columns, R10.1), the {@link MatchTier}, the <b>derived tier
 * percent</b> ({@code EXACT}=100 / {@code MANUFACTURER_CATEGORY}=80 / {@code CATEGORY}=50 /
 * {@code NONE}=0 — a presentational label derived from the tier, never a persisted free score,
 * R5.8), and the {@link MatchStatus}.
 *
 * <p>This is a read-only projection: the review surface on the {@code procurement} tab is read-only
 * (no confirm / re-link / classify — those are FOR-06-04), so there is no write-path counterpart.
 *
 * @param lineNo             the sequential 1-based position within the document
 * @param rawName            the verbatim OCR product text
 * @param quantity           the parsed quantity (nullable, {@code >= 0})
 * @param unitHint           the verbatim OCR unit text (nullable)
 * @param unitPrice          the parsed per-unit price (nullable, {@code >= 0})
 * @param lineTotal          the parsed line total (nullable, {@code >= 0})
 * @param manufacturerHint   the verbatim OCR manufacturer/brand hint (nullable)
 * @param matchedMaterialId  the matched {@code Concrete_Material} id (null for a {@code NONE}-tier line)
 * @param matchedMaterialName the matched material's localized display name (null when unmatched)
 * @param matchTier          the confidence tier assigned by the {@code MatchEngine}
 * @param matchPercent       the derived tier percent (100 / 80 / 50 / 0)
 * @param matchStatus        {@code AUTO} at a non-{@code NONE} tier, else {@code UNMATCHED}
 */
public record ProcurementLineModel(
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
        MatchStatus matchStatus) {
}
