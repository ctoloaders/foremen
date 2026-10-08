package com.foremen.dao;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.DocumentMediaEntity;

/**
 * DAO for {@link DocumentMediaEntity} (FOR-05-08, Requirements 7.1, 7.4), following the
 * {@code OfferDiscountDao} convention: the generic {@link AdminDao} CRUD surface plus a
 * document-scoped finder. Media is a collection child of its document (owner FK {@code document_id},
 * ON DELETE CASCADE) and resolves to a project via {@code document.project.id}; deleting the media
 * backing a {@code SIGNED} signature is rejected at the service layer (R7.4).
 */
@Repository
public interface DocumentMediaDao extends AdminDao<DocumentMediaEntity, Long> {

    /**
     * Every media row of a document — backs the signing-tab media listing and the signed-evidence
     * delete lock (R7.1, R7.4).
     *
     * @param documentId the owning document id
     * @return the document's media
     */
    List<DocumentMediaEntity> findByDocumentId(Long documentId);
}
