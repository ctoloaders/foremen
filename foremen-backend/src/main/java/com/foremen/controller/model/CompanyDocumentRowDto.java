package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.service.procurement.LinkStatus;
import com.foremen.service.procurement.ProcurementLedgerEntryDto;

/**
 * FOR-07-03 (Requirement 4.4) - one document row of the company-wide purchases screen. The project
 * fields are {@code null} for a no-project (company) expense. {@code linkCounts} always carries all
 * four {@link LinkStatus} keys and sums to {@code lineCount}. The signed image URLs are populated only
 * by the single-document read (R14.2); list rows carry {@code hasImages} instead.
 */
public record CompanyDocumentRowDto(
        Long id,
        Long projectId,
        String projectName,
        String supplierName,
        BigDecimal documentTotal,
        ProcessingStatus status,
        String failureReason,
        OffsetDateTime uploadedAt,
        String uploaderName,
        Long expenseCategoryId,
        String expenseCategoryName,
        DocumentPaymentStatus paymentStatus,
        List<ProcurementLedgerEntryDto> ledgerEntries,
        int lineCount,
        Map<LinkStatus, Integer> linkCounts,
        boolean hasImages,
        String rawImageUrl,
        String processedImageUrl) {
}
