package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.foremen.dao.model.ProcessingStatus;

/**
 * FOR-06-03 (Requirements 2.5, 5.8; design §5.6) — the response of a successful upload
 * ({@code POST /api/procurement-documents}, multipart {@code projectId} + {@code file}). The upload
 * stores the raw file, creates the document, and triggers OCR inline, so the response echoes the
 * resulting document state: the new {@code id}, the project, the uploader + localized name, the
 * lifecycle {@code status} ({@code PROCESSING} while OCR runs, or {@code PARSED}/{@code FAILED} once
 * the inline pass completes, {@code UPLOADED} when the OCR provider is disabled, R3.5), the
 * parse-derived supplier/total (when {@code PARSED}), the {@code uploadedAt}/{@code parsedAt}
 * timestamps, the stable {@code failureReason} (on {@code FAILED}), the resolved raw + processed
 * image URLs, and the ordered per-line projection with proposed matches (empty until {@code PARSED}).
 *
 * <p>Shape-compatible with {@link ProcurementDocumentDtoExtendedModel} so the client can render the
 * uploaded document immediately; the fresh projection is also fetchable via
 * {@code GET /api/procurement-documents/{id}}.
 */
public record ProcurementDocumentUploadResponse(
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
