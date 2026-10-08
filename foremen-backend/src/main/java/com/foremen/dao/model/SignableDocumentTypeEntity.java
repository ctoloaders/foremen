package com.foremen.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-05-08 (Requirements 2.1, 2.2, 2.5, 12.1, 12.3): the SignableDocumentType reference — a GLOBAL
 * (not project-scoped) i18n dictionary of the kinds of document the platform signs. Maps the
 * {@code signable_document_types} table (changeset {@code 137-create-signable-document-types.xml}).
 *
 * <p>The body/signing lifecycle binds to a type by its {@link #code}; contract types are matched by
 * the {@code CONTRACT_*} prefix for the activation hand-off (R10.1). {@link #namePL} is the only
 * backend i18n field and the display fallback; {@link #nameRU} is optional. A type is deactivated
 * ({@link #active} = false), never deleted (R2.5).
 */
@Entity
@Table(name = "signable_document_types")
@Getter
@Setter
@NoArgsConstructor
public class SignableDocumentTypeEntity extends BaseEntity {

    /** Stable business key, UNIQUE NOT NULL; matched by the {@code CONTRACT_*} prefix (R2.1, R10.1). */
    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    /** The only backend i18n field (PL), the display fallback (R2.1, R2.2, R12.1, R12.3). */
    @Column(name = "name_pl", nullable = false, length = 255)
    private String namePL;

    /** Optional i18n (RU) display name (R2.2, R2.3). */
    @Column(name = "name_ru", length = 255)
    private String nameRU;

    /** Deactivated, never deleted; defaults to active (R2.5). */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Optional per-type default eIDAS level (R2.1). */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_signature_level", length = 8)
    private SignatureLevel defaultSignatureLevel;
}
