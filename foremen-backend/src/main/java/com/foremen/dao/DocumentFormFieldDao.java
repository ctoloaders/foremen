package com.foremen.dao;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.DocumentFormFieldEntity;

/**
 * DAO for {@link DocumentFormFieldEntity} (FOR-05-08, Requirements 3.6, 1.5), following the
 * {@code OfferDiscountDao} convention: the generic {@link AdminDao} CRUD surface plus a
 * document-scoped finder. Form fields are a collection child of their document (owner FK
 * {@code document_id}, ON DELETE CASCADE) and resolve to a project via {@code document.project.id}.
 */
@Repository
public interface DocumentFormFieldDao extends AdminDao<DocumentFormFieldEntity, Long> {

    /**
     * The form fields declared on a document — normally reached through the parent document's
     * {@code formFields} collection; exposed for the fill-form-fields path.
     *
     * @param documentId the owning document id
     * @return the document's form fields
     */
    List<DocumentFormFieldEntity> findByDocumentId(Long documentId);
}
