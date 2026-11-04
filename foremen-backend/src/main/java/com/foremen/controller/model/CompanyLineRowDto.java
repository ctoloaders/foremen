package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;
import com.foremen.service.procurement.LinkStatus;

/**
 * FOR-07-03 (Requirement 4.5) - one line row of the company-wide purchases screen: the line fields,
 * the owning document's supplier / upload time / project, the matched material and the unplanned
 * category, and the derived {@code linkStatus}.
 */
public record CompanyLineRowDto(
        Long lineId,
        Long documentId,
        String documentSupplierName,
        OffsetDateTime documentUploadedAt,
        Long projectId,
        String projectName,
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
        MatchStatus matchStatus,
        Long unplannedCategoryId,
        String unplannedCategoryName,
        LinkStatus linkStatus) {
}
