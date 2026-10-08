package com.foremen.dao;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.DocumentSignatureEntity;

/**
 * DAO for {@link DocumentSignatureEntity} (FOR-05-08, Requirements 4.1, 6.6), following the
 * {@code OfferDiscountDao} convention: the generic {@link AdminDao} CRUD surface plus a
 * document-scoped finder. A signature is a collection child of its document (owner FK
 * {@code document_id}, ON DELETE CASCADE) and resolves to a project via {@code document.project.id};
 * {@code recomputeSignedState} reads the full signature set of a document.
 */
@Repository
public interface DocumentSignatureDao extends AdminDao<DocumentSignatureEntity, Long> {

    /**
     * Every signature of a document — the input to {@code recomputeSignedState} and
     * {@code SigningProgressCalculator} (R6.6, R4.5).
     *
     * @param documentId the owning document id
     * @return the document's signatures
     */
    List<DocumentSignatureEntity> findByDocumentId(Long documentId);
}
