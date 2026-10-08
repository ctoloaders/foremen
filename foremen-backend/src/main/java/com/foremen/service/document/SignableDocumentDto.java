package com.foremen.service.document;

import java.time.LocalDateTime;
import java.util.List;

import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.SignatureLevel;

/**
 * FOR-05-08 (Requirements 1.1, 1.2, 4.5, 9.6, 13.1, 13.3): the primary read model of a
 * {@code SignableDocument} returned by every {@code /api/signable-documents} response. It exposes the
 * lifecycle facts, the resolved {@code documentTypeCode}, and the three nested child collections
 * (signatures, media summaries, form fields) plus the derived {@link SigningProgressDto}.
 *
 * <p>{@link #status} and {@link #signatureLevel} are enums localized on the frontend (no DB i18n
 * columns, R12.1). {@code SIGNED} is a derived consequence of every signature being {@code SIGNED}
 * (parent Property 16), never set imperatively.
 *
 * <p><b>Confidentiality (R9.6, R13.3; FOR-05-07 invariant):</b> this is the client-reachable payload.
 * Neither this record nor any type in its graph ({@link DocumentSignatureDto},
 * {@link DocumentMediaSummaryDto}, {@link FormFieldDto}, {@link SigningProgressDto}) carries any
 * cost, estimate, or margin field — the originating object appears only as the opaque
 * {@link #sourceRef} string (e.g. an offer id), never an embedded estimate/offer DTO.
 *
 * @param id               the document id
 * @param projectId        the owning project id
 * @param documentTypeCode the bound document type's business code
 * @param status           the lifecycle status
 * @param signatureLevel   the document-level default eIDAS level, or {@code null}
 * @param title            the optional document title, or {@code null}
 * @param sourceRef        the optional opaque originating-object reference, or {@code null}
 * @param templateLocale   the locale used at generation, or {@code null}
 * @param documentUri      the DRAFT body / frozen PDF uri, or {@code null}
 * @param contentHash      the sha-256 of the body / frozen PDF, or {@code null}
 * @param createdAt        the creation timestamp
 * @param signatures       the designated signatures
 * @param media            the evidence/attachment media summaries
 * @param formFields       the declared fill-in form fields
 * @param progress         the derived aggregate signing progress
 */
public record SignableDocumentDto(
        Long id,
        Long projectId,
        String documentTypeCode,
        DocumentStatus status,
        SignatureLevel signatureLevel,
        String title,
        String sourceRef,
        String templateLocale,
        String documentUri,
        String contentHash,
        LocalDateTime createdAt,
        List<DocumentSignatureDto> signatures,
        List<DocumentMediaSummaryDto> media,
        List<FormFieldDto> formFields,
        SigningProgressDto progress) {
}
