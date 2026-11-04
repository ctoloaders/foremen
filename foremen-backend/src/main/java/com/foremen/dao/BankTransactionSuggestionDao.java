package com.foremen.dao;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.BankTransactionSuggestionEntity;
import com.foremen.dao.model.SuggestionStatus;

/**
 * FOR-07-04 (Requirement 13.7) — DAO for {@link BankTransactionSuggestionEntity}.
 */
@Repository
public interface BankTransactionSuggestionDao extends AdminDao<BankTransactionSuggestionEntity, Long> {

    /** Suggestions of a line in a status, by rank. */
    List<BankTransactionSuggestionEntity> findByTransactionIdAndStatusOrderByRankAsc(Long transactionId,
                                                                                     SuggestionStatus status);

    /** Suggestions of many lines in a status (list view batch). */
    List<BankTransactionSuggestionEntity> findByTransactionIdInAndStatus(Collection<Long> transactionIds,
                                                                         SuggestionStatus status);

    /** Number of suggestions of a line in a status. */
    long countByTransactionIdAndStatus(Long transactionId, SuggestionStatus status);
}
