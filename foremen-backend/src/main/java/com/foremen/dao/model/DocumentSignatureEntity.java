package com.foremen.dao.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-05-08 (Requirements 4.1, 4.4, 5.1, 5.2, 6.1, 6.5): the DocumentSignature child — one row per
 * designated signer. Maps the {@code document_signatures} table (changeset
 * {@code 143-create-document-signatures.xml}).
 *
 * <p>A document is {@link DocumentStatus#SIGNED} iff every one of its signatures is
 * {@link SignatureStatus#SIGNED} (parent Property 16). {@link #signerUser} is nullable — a null
 * explicit signer is resolved by {@link #signerRole} (e.g. {@code CLIENT} resolves to the project's
 * CLIENT member(s), sign-any, R4.3 / decision 8). Project-scoped via {@code document.project.id}.
 */
@Entity
@Table(name = "document_signatures")
@Getter
@Setter
@NoArgsConstructor
public class DocumentSignatureEntity extends BaseEntity {

    /** Owner document FK, NOT NULL; a signature cannot outlive its document. Scope via document.project.id (R4.1). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private SignableDocumentEntity document;

    /** Explicit signer; null ⇒ resolved by {@link #signerRole} (R4.1, R4.3). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signer_user_id")
    private UserEntity signerUser;

    /** e.g. {@code CLIENT}; CLIENT resolves to project CLIENT member(s), sign-any (R4.3, decision 8); nullable. */
    @Column(name = "signer_role", length = 16)
    private String signerRole;

    /** Signing method, NOT NULL (R4.1, R6.1). */
    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 24)
    private SignatureMethod method;

    /** eIDAS level, NOT NULL; defaults to {@code AdES} (R4.1, R5.1, R5.2). */
    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 8)
    private SignatureLevel level = SignatureLevel.AdES;

    /** Provider session ref for {@code ONLINE}/{@code PODPIS_GOV_PL} (R4.1); nullable. */
    @Column(name = "provider_ref", length = 255)
    private String providerRef;

    /** Set on completion, every method (R4.1, R6.5); nullable until signed. */
    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    /** Scan / tablet image / sealed PDF uri (R4.1); nullable until signed. */
    @Column(name = "evidence_uri", length = 512)
    private String evidenceUri;

    /** Signature status, NOT NULL; defaults to {@code PENDING} (R4.1, R4.4). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SignatureStatus status = SignatureStatus.PENDING;

    /** Reason on {@code DECLINED} (R4.4); nullable. */
    @Column(name = "decline_reason", columnDefinition = "TEXT")
    private String declineReason;
}
