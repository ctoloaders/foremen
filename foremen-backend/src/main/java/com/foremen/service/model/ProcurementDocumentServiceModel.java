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
 * FOR-06-03 (Requirements 5.8, 10.1; design §5.6) — read-path service model for a
 * {@code ProcurementDocument}, satisfying the {@code ProjectScopedService}/{@code AdminService} CRUD
 * contract {@code ProcurementDocumentService} implements (entity-creation-rules step 4).
 *
 * <p>Carries the flat {@code projectId} FK, the uploader id plus the uploader's
 * <b>localized display name</b> ({@code uploaderName}), the lifecycle {@code status} and the
 * parse-derived {@code supplierName}/{@code documentTotal}, the {@code uploadedAt}/{@code parsedAt}
 * timestamps, the stable {@code failureReason} code, the resolved <b>raw + processed image URLs</b>
 * (filled by the service via {@code ImageStorage.toCdnUrl} / {@code signedDownloadUrl} — the entity
 * stores only the object keys), and the ordered per-line projection ({@code lines}) each carrying
 * its matched-material localized name, tier, derived tier percent and match status.
 *
 * <p>This spec owns intake + automatic matching only; the review surface is read-only, so there is
 * a single read model (list + detail share the same shape, the list simply omits {@code lines}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcurementDocumentServiceModel {
    private Long id;

    private Long projectId;

    /** Who uploaded the document (R2.1). */
    private Long uploadedById;
    /** The uploader's display name. */
    private String uploaderName;

    /** Document-level supplier/store name from the parse; nullable. */
    private String supplierName;
    /** Document-level total from the parse; nullable, {@code >= 0}. */
    private BigDecimal documentTotal;

    /** The OCR/parse lifecycle status ({@code UPLOADED}/{@code PROCESSING}/{@code PARSED}/{@code FAILED}). */
    private ProcessingStatus status;
    /** A stable failure code surfaced to the UI on {@code FAILED}; nullable. */
    private String failureReason;

    private OffsetDateTime uploadedAt;
    /** When the parse succeeded; nullable until {@code PARSED}. */
    private OffsetDateTime parsedAt;

    /** Resolved URL of the raw upload (service-filled from {@code rawObjectKey}); nullable. */
    private String rawImageUrl;
    /** Resolved URL of the processed white-background rendition; nullable until cleanup runs. */
    private String processedImageUrl;

    /** The ordered per-line extracted items with their proposed matches (R5.8); empty until {@code PARSED}. */
    private List<ProcurementLineModel> lines = new ArrayList<>();
}
