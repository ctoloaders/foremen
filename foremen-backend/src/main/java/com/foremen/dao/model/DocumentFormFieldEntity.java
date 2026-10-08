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
 * FOR-05-08 (Requirements 3.6, 1.5, 8.4): the DocumentFormField child — a declared fill-in blank on
 * a document (template-declared, copied onto the document at generation). Maps the
 * {@code document_form_fields} table (changeset {@code 142-create-document-form-fields.xml}).
 *
 * <p>Form fields are part of the frozen artifact and are the ONLY textual content a signer may add
 * after the freeze — {@link #value} is editable only while the document is {@code PENDING_SIGNATURES}
 * (R1.5). Project-scoped via {@code document.project.id}.
 */
@Entity
@Table(name = "document_form_fields")
@Getter
@Setter
@NoArgsConstructor
public class DocumentFormFieldEntity extends BaseEntity {

    /** Owner document FK, NOT NULL; a form field cannot outlive its document. Scope via document.project.id. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private SignableDocumentEntity document;

    /** Field key, NOT NULL (e.g. {@code pesel}, {@code idDocNumber}). */
    @Column(name = "key", nullable = false, length = 128)
    private String key;

    /** Display label (localized on FE); nullable. */
    @Column(name = "label", length = 255)
    private String label;

    /** Which signer role may fill it (CLIENT own-fields constraint, R8.4); nullable. */
    @Column(name = "owner_role", length = 16)
    private String ownerRole;

    /** Filled value; editable only while {@code PENDING_SIGNATURES} (R1.5); nullable. */
    @Column(name = "value", columnDefinition = "TEXT")
    private String value;
}
