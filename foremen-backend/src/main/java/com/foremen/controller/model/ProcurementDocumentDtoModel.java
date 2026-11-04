package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.service.procurement.ProcurementLedgerEntryDto;

/**
 * FOR-06-03 (Requirements 5.8, 10.1; design §5.6) — list-row DTO for a {@code ProcurementDocument},
 * the projection returned in the project-scoped document list on the {@code procurement} tab.
 *
 * <p>Carries the id, the project FK, the uploader id + localized display name, the parse-derived
 * {@code supplierName}/{@code documentTotal}, the lifecycle {@code status}, the {@code uploadedAt}
 * timestamp, and the stable {@code failureReason} code. The full per-line split, the parse
 * timestamp, and the resolved image URLs live on {@link ProcurementDocumentDtoExtendedModel}.
 */
public record ProcurementDocumentDtoModel(
        Long id,
        Long projectId,
        Long uploadedById,
        String uploaderName,
        String supplierName,
        BigDecimal documentTotal,
        ProcessingStatus status,
        String failureReason,
        OffsetDateTime uploadedAt,
        // FOR-07-04 (R18.3, R28.4) — invoice fields, derived payment status, linked ledger lines.
        String supplierNip,
        String invoiceNumber,
        LocalDate invoiceDate,
        DocumentPaymentStatus paymentStatus,
        List<ProcurementLedgerEntryDto> ledgerEntries) {
}
