package com.foremen.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.SignableDocumentTypeEntity;

/**
 * DAO for {@link SignableDocumentTypeEntity} (FOR-05-08, Requirements 2.1, 2.3), following the
 * {@code OfferDao} finder convention: the generic {@link AdminDao} CRUD surface plus code-keyed
 * finders backing the type catalog in {@code SignableDocumentTypeService} and the merge/activation
 * lookups.
 */
@Repository
public interface SignableDocumentTypeDao extends AdminDao<SignableDocumentTypeEntity, Long> {

    /**
     * The type with the given stable business {@code code} (unique), if any — the primary lookup
     * for binding a document to its type and matching the {@code CONTRACT_*} prefix (R2.1, R10.1).
     *
     * @param code the stable business key
     * @return the matching type, or empty
     */
    Optional<SignableDocumentTypeEntity> findByCode(String code);

    /**
     * The active types, ordered by {@code code} — backs the type-catalog listing (R2.3, R2.5).
     *
     * @return the active types, oldest code first
     */
    List<SignableDocumentTypeEntity> findByActiveTrueOrderByCodeAsc();
}
