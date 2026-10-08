package com.foremen.service.document;

import java.time.LocalDateTime;

/**
 * FOR-05-08 (Requirements 3.1, 3.2, 3a.5): the read model of a {@code DocumentTemplate} — a global,
 * versioned source body bound to a document type and locale, served by the admin
 * {@code DocumentTemplate} CRUD surface (ADMIN-only; not project-scoped).
 *
 * <p>At most one template is {@link #active} per ({@code documentTypeCode}, {@code locale}) (R3.2);
 * {@link #version} is bumped on edit with prior versions retained (R3a.5); templates are deactivated,
 * never deleted.
 *
 * @param id               the template id
 * @param documentTypeId   the bound document type id
 * @param documentTypeCode the bound document type's business code
 * @param name             the template name
 * @param locale           {@code PL} / {@code RU} / {@code BILINGUAL}
 * @param storageUri       the stored source file uri (object storage)
 * @param version          the version number
 * @param active           whether this is the active template for its (type, locale)
 * @param uploadedById     the uploader user id
 * @param uploadedAt       the upload timestamp
 */
public record DocumentTemplateDto(
        Long id,
        Long documentTypeId,
        String documentTypeCode,
        String name,
        String locale,
        String storageUri,
        Integer version,
        boolean active,
        Long uploadedById,
        LocalDateTime uploadedAt) {
}
