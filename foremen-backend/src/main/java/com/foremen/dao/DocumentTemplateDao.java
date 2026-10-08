package com.foremen.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.DocumentTemplateEntity;

/**
 * DAO for {@link DocumentTemplateEntity} (FOR-05-08, Requirements 3.1, 3.2, 3a.5), following the
 * {@code OfferDao} finder convention: the generic {@link AdminDao} CRUD surface plus the finders
 * backing the versioned, one-active-per-(type, locale) template admin in
 * {@code DocumentTemplateService} and the merge generation lookup.
 */
@Repository
public interface DocumentTemplateDao extends AdminDao<DocumentTemplateEntity, Long> {

    /**
     * The single active template for a ({@code documentType}, {@code locale}) pair, if any — the
     * generation source. The one-active-per-(type, locale) invariant is enforced by the partial
     * unique index {@code ux_document_templates_active_type_locale} (R3.2).
     *
     * @param documentTypeId the owning type id
     * @param locale         the template locale
     * @return the active template, or empty
     */
    Optional<DocumentTemplateEntity> findByDocumentTypeIdAndLocaleAndActiveTrue(Long documentTypeId, String locale);

    /**
     * Every template version (active and inactive) for a ({@code documentType}, {@code locale}) pair,
     * newest version first — backs the version history (R3a.5).
     *
     * @param documentTypeId the owning type id
     * @param locale         the template locale
     * @return the templates, newest version first
     */
    List<DocumentTemplateEntity> findByDocumentTypeIdAndLocaleOrderByVersionDesc(Long documentTypeId, String locale);
}
