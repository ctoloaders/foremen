package com.foremen.dao.model;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-05-08 (Requirements 1.1, 1.2, 1.8, 3.5, 5.2): the SignableDocument domain root — one row per
 * document instance, PROJECT-SCOPED ({@code getProjectIdPath() = "project.id"}), bound to a
 * {@link SignableDocumentTypeEntity} and progressing through the
 * {@code DRAFT → PENDING_SIGNATURES → SIGNED} (+ {@code VOID}) lifecycle. Maps the
 * {@code signable_documents} table (changeset {@code 141-create-signable-documents.xml}).
 *
 * <p>{@link #status} is {@link DocumentStatus#SIGNED} only as a <b>derived</b> consequence of every
 * {@link #signatures} entry being {@code SIGNED} ({@code recomputeSignedState}), never assigned
 * imperatively. {@link #contentHash} is the sha-256 of the DRAFT body / frozen PDF, stable once the
 * document enters {@code PENDING_SIGNATURES}. The signature/media/form-field children scope through
 * {@code document.project.id}.
 */
@Entity
@Table(name = "signable_documents")
@Getter
@Setter
@NoArgsConstructor
public class SignableDocumentEntity extends BaseEntity {

    /** Scope root FK to the owning project, NOT NULL; {@code getProjectIdPath() = "project.id"} (R1.1, R1.8). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    /** Type binding FK, NOT NULL; a referenced type is deactivated, never hard-deleted (R1.1, R2.5). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_type_id", nullable = false)
    private SignableDocumentTypeEntity documentType;

    /** Lifecycle status (R1.2); defaults to {@code DRAFT}. {@code SIGNED} is derived, never set imperatively. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DocumentStatus status = DocumentStatus.DRAFT;

    /** Optional document title (R1.1). */
    @Column(name = "title", length = 255)
    private String title;

    /** Optional originating-object ref, e.g. an offer id (R1.1, R14.1). */
    @Column(name = "source_ref", length = 255)
    private String sourceRef;

    /** Document-level default eIDAS level; {@code AdES} default applied at the service layer (R5.2). */
    @Enumerated(EnumType.STRING)
    @Column(name = "signature_level", length = 8)
    private SignatureLevel signatureLevel;

    /** Locale used at generation (R3.2). */
    @Column(name = "template_locale", length = 16)
    private String templateLocale;

    /** DRAFT body / frozen PDF uri in object storage (R1.1, R3.5). */
    @Column(name = "document_uri", length = 512)
    private String documentUri;

    /** sha-256 of the body / frozen PDF (R1.1, R3.5, R6.4). */
    @Column(name = "content_hash", length = 128)
    private String contentHash;

    /** Owner provenance FK, NOT NULL (R1.1, R11.2). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private UserEntity createdByUser;

    /**
     * The designated signatures of this document — a cascade/collection child (R4.1). A document is
     * {@code SIGNED} iff every one of these is {@code SIGNED} (parent Property 16).
     * {@code cascade = ALL} + {@code orphanRemoval = true} mirror the DB-level ON DELETE CASCADE on
     * {@code document_signatures.document_id}.
     */
    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DocumentSignatureEntity> signatures = new ArrayList<>();

    /**
     * The declared fill-in form fields of this document — a cascade/collection child (R3.6).
     * {@code cascade = ALL} + {@code orphanRemoval = true} mirror the DB-level ON DELETE CASCADE on
     * {@code document_form_fields.document_id}.
     */
    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DocumentFormFieldEntity> formFields = new ArrayList<>();

    /**
     * The evidence/attachment media of this document — a cascade/collection child (R7.1).
     * {@code cascade = ALL} + {@code orphanRemoval = true} mirror the DB-level ON DELETE CASCADE on
     * {@code document_media.document_id}.
     */
    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DocumentMediaEntity> media = new ArrayList<>();
}
