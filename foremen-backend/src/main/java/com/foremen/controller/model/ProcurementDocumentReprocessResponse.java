package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.foremen.dao.model.ProcessingStatus;

/**
 * FOR-06-03 (Requirements 8.1, 8.2, 5.8; design §5.6) — the response of a re-process request
 * ({@code POST /api/procurement-documents/{id}/reprocess}). Re-process re-runs OCR + match on the
 * already-stored raw bytes (no re-upload), regenerates the processed rendition, and replaces all
 * lines atomically, so the response echoes the refreshed document state: the {@code id}, the
 * project, the uploader + localized name, the new lifecycle {@code status}
 * ({@code PARSED}/{@code FAILED}), the parse-derived supplier/total, the {@code uploadedAt}/
 * {@code parsedAt} timestamps, the stable {@code failureReason} (on {@code FAILED}), the resolved
 * raw + regenerated processed image URLs, and the fresh ordered per-line projection with proposed
 * matches.
 *
 * <p>Shape-compatible with {@link ProcurementDocumentDtoExtendedModel} so the client can re-render
 * the document immediately after re-processing.
 */
public record ProcurementDocumentReprocessResponse(
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
