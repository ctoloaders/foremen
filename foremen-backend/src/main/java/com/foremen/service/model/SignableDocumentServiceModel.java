package com.foremen.service.model;

import java.time.LocalDateTime;

import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.SignatureLevel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-path service model for a {@code SignableDocument} (FOR-05-08, Requirement 1), satisfying the
 * {@code ProjectScopedService}/{@code AdminService} CRUD contract that {@code SignableDocumentService}
 * implements.
 *
 * <p>Following the FOR-05-07 {@code OfferServiceModel} convention: flat FK ids paired with the
 * resolved {@code documentTypeCode} fact, the {@code status} enum, and the lifecycle facts. This flat
 * model exists only to back the inherited CRUD surface; the rich client-reachable projection is the
 * {@code SignableDocumentDto} assembled by the service.
 *
 * <p><b>Confidentiality (R9.6, R13.3):</b> carries no cost/estimate/margin field — the originating
 * object appears only as the opaque {@code sourceRef} string.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SignableDocumentServiceModel {
    private Long id;
    private Long projectId;
    private Long documentTypeId;
    private String documentTypeCode;
    private DocumentStatus status;
    private SignatureLevel signatureLevel;
    private String title;
    private String sourceRef;
    private String templateLocale;
    private String documentUri;
    private String contentHash;
    private LocalDateTime createdAt;
}
