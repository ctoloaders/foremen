package com.foremen.service.document;

import com.foremen.dao.model.SignatureLevel;

/**
 * FOR-05-08 (Requirements 2.1, 2.2, 2.3, 2.5): the read model of a {@code SignableDocumentType} — a
 * GLOBAL (not project-scoped) i18n dictionary entry describing a kind of document the platform
 * signs, served by the ADMIN-only {@code SignableDocumentType} CRUD surface.
 *
 * <p>{@link #namePL} is the only mandatory i18n field and the display fallback; {@link #nameRU} is
 * optional (R2.2). A type is deactivated ({@link #active} = {@code false}), never deleted (R2.5).
 *
 * @param id                    the type id
 * @param code                  the stable business key (unique; {@code CONTRACT_*} prefix drives the
 *                              activation hand-off, R10.1)
 * @param namePL                the PL display name (mandatory i18n field + fallback)
 * @param nameRU                the optional RU display name
 * @param active                whether the type is active
 * @param defaultSignatureLevel the optional per-type default eIDAS level
 */
public record SignableDocumentTypeDto(
        Long id,
        String code,
        String namePL,
        String nameRU,
        boolean active,
        SignatureLevel defaultSignatureLevel) {
}
