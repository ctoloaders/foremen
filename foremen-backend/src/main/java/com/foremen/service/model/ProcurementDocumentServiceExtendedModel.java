package com.foremen.service.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.foremen.controller.model.ProcurementLineModel;
import com.foremen.dao.model.ProcessingStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FOR-06-03 (Requirements 5.8, 10.1; design §5.6) — the extended service model for a
 * {@code ProcurementDocument}, satisfying the {@code ProjectScopedService}/{@code AdminService} CRUD
 * contract {@code ProcurementDocumentService} implements (entity-creation-rules step 4).
 *
 * <p>Procurement intake is file-driven: a document is created by {@code upload(projectId,
 * MultipartFile)} and never by a JSON create/update body, so the <b>write path</b> carries only the
 * {@code projectId} (the raw bytes, content type, uploader, lifecycle, OCR result, and per-line
 * matches are all derived server-side by {@code ProcurementDocumentService}). The remaining fields
 * form the single-read projection (the review view for {@code GET /{id}}): the uploader id + the
 * localized {@code uploaderName}, the parse-derived supplier/total, the lifecycle {@code status} and
 * {@code failureReason}, the {@code uploadedAt}/{@code parsedAt} timestamps, the resolved raw +
 * processed image URLs, and the ordered per-line projection with proposed matches.
 *
 * <p>The read surface is read-only (no confirm / re-link / classify — those are FOR-06-04), so no
 * author-supplied fields other than {@code projectId} exist on the write path.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcurementDocumentServiceExtendedModel {
    private Long id;

    private Long projectId;

    // --- Read-side projection (FOR-06-03 single-document review view) ---
    //
    // On the WRITE path these stay null (the service derives/sets them from the upload + OCR); on
    // the READ path the service mapper + service populate them so GET /{id} returns the lifecycle,
    // the parse-derived supplier/total, the resolved image URLs, the uploader name, and the ordered
    // lines with their proposed matches.

    /** Who uploaded the document (read path). */
    private Long uploadedById;
    /** The uploader's display name (read path). */
    private String uploaderName;

    /** Document-level supplier/store name from the parse (read path); nullable. */
    private String supplierName;
    /** Document-level total from the parse (read path); nullable, {@code >= 0}. */
    private BigDecimal documentTotal;

    /** The OCR/parse lifecycle status (read path). */
    private ProcessingStatus status;
    /** A stable failure code surfaced to the UI on {@code FAILED} (read path); nullable. */
    private String failureReason;

    private OffsetDateTime uploadedAt;
    /** When the parse succeeded (read path); nullable until {@code PARSED}. */
    private OffsetDateTime parsedAt;

    /** Resolved URL of the raw upload (service-filled from {@code rawObjectKey}, read path); nullable. */
    private String rawImageUrl;
    /** Resolved URL of the processed white-background rendition (read path); nullable until cleanup. */
    private String processedImageUrl;

    /** The ordered per-line extracted items with their proposed matches (read path, R5.8); empty until {@code PARSED}. */
    private List<ProcurementLineModel> lines = new ArrayList<>();
}
