package com.foremen.service.model;

import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.SignatureLevel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Write-path service model for a {@code SignableDocument} (FOR-05-08, Requirement 1), satisfying the
 * {@code ProjectScopedService}/{@code AdminService} CRUD contract that {@code SignableDocumentService}
 * implements.
 *
 * <p>Follows the FOR-05-07 {@code OfferServiceExtendedModel} convention: mutable ({@code @Data}) flat
 * FK ids plus the lifecycle fields. The document lifecycle itself
 * (create/generate/saveBody/request-signatures/fill-form-fields/void) is driven through
 * {@code SignableDocumentService}'s dedicated methods, not raw CRUD writes; this model exists only to
 * satisfy the generic CRUD contract (its {@code status}/{@code contentHash}/{@code documentUri} are
 * never hand-written through generic CRUD).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SignableDocumentServiceExtendedModel {
    private Long id;
    private Long projectId;
    private Long documentTypeId;
    private DocumentStatus status;
    private SignatureLevel signatureLevel;
    private String title;
    private String sourceRef;
    private String templateLocale;
    private String documentUri;
    private String contentHash;
}
