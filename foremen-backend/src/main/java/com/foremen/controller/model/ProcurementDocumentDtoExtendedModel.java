package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.foremen.dao.model.ProcessingStatus;

/**
 * FOR-06-03 (Requirements 5.8, 10.1; design §5.6) — detail DTO for a single
 * {@code ProcurementDocument}, the projection returned for the single-document read-only review view
 * ({@code GET /api/procurement-documents/{id}}).
 *
 * <p>Extends the list row with the parse timestamp, the resolved <b>raw + processed image URLs</b>
 * (resolved by the service from the stored object keys), and the ordered per-line split
 * ({@code lines}), each {@link ProcurementLineModel} carrying its verbatim OCR fields, the proposed
 * matched-material localized name ({@code ru → pl → code}), the {@code matchTier}, the derived tier
 * percent (100 / 80 / 50 / 0), and the {@code matchStatus}. The review is read-only (no confirm /
 * re-link / classify — those are FOR-06-04).
 */
public record ProcurementDocumentDtoExtendedModel(
        Long id,
        Long projectId,
        Long uploadedById,
        String uploaderName,
        String supplierName,
        BigDecimal documentTotal,
        ProcessingStatus status,
        String failureReason,
        OffsetDateTime uploadedAt,
        OffsetDateTime parsedAt,
        String rawImageUrl,
        String processedImageUrl,
        List<ProcurementLineModel> lines) {
}
