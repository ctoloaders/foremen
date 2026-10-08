package com.foremen.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-05-08 (Requirements 3.1, 3.2, 3a.5): the DocumentTemplate reference — a GLOBAL (not
 * project-scoped), VERSIONED store of the source body (with {@code {Token}} merge placeholders)
 * bound to a document type and locale. Maps the {@code document_templates} table (changeset
 * {@code 139-create-document-templates.xml}).
 *
 * <p>A partial unique index enforces AT MOST ONE active template per ({@link #documentType},
 * {@link #locale}) (R3.2); any number of inactive (prior-version / superseded) templates may
 * coexist. {@link #version} is bumped on edit with prior versions retained (R3a.5); templates are
 * deactivated ({@link #active} = false), never deleted.
 */
@Entity
@Table(name = "document_templates")
@Getter
@Setter
@NoArgsConstructor
public class DocumentTemplateEntity extends BaseEntity {

    /** Owner type FK, NOT NULL; a template cannot outlive its type (R3.1). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_type_id", nullable = false)
    private SignableDocumentTypeEntity documentType;

    /** Template name, NOT NULL. */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Locale — {@code PL} / {@code RU} / {@code BILINGUAL} (R3.1). */
    @Column(name = "locale", nullable = false, length = 16)
    private String locale;

    /** Stored source file in object storage, NOT NULL (R3.1). */
    @Column(name = "storage_uri", nullable = false, length = 512)
    private String storageUri;

    /** Bumped on edit, prior versions retained; defaults to 1 (R3a.5). */
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    /** Exactly one active per (documentType, locale); defaults to active (R3.2). */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Uploader provenance FK, NOT NULL (R3.1). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_id", nullable = false)
    private UserEntity uploadedBy;
}
