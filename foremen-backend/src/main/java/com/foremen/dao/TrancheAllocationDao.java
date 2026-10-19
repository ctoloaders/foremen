package com.foremen.dao;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.TrancheAllocationEntity;

/**
 * DAO for {@link TrancheAllocationEntity} (FOR-06-07, Requirements 5.7, 6.2): the append-only
 * settlement link between a transaction and a tranche. Follows the {@code AdminDao} CRUD convention
 * plus per-transaction / per-tranche finders.
 *
 * <p>Allocations are append-only and immutable once their tranche is {@code PAID} (R6.2); they are
 * normally reached through the parent transaction's {@code allocations} collection. The finders back
 * the per-transaction load and the reverse per-tranche settlement lookup.
 */
@Repository
public interface TrancheAllocationDao extends AdminDao<TrancheAllocationEntity, Long> {

    /**
     * The allocations written for a transaction.
     *
     * @param transactionId the owning transaction id
     * @return the transaction's allocations
     */
    List<TrancheAllocationEntity> findByTransactionId(Long transactionId);

    /**
     * The allocations accumulated against a tranche across transactions.
     *
     * @param trancheId the settled tranche id
     * @return the tranche's allocations
     */
    List<TrancheAllocationEntity> findByTrancheId(Long trancheId);
}
