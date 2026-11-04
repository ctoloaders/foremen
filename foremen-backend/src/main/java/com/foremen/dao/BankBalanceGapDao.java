package com.foremen.dao;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.BankBalanceGapEntity;

/**
 * FOR-07-04 (Requirement 7.2) — DAO for {@link BankBalanceGapEntity}.
 */
@Repository
public interface BankBalanceGapDao extends AdminDao<BankBalanceGapEntity, Long> {

    /** Gaps of an account, newest first. */
    List<BankBalanceGapEntity> findByAccountIdOrderByIdDesc(Long accountId);

    /** Unacknowledged gaps of an account (drives {@code has_balance_gap}). */
    List<BankBalanceGapEntity> findByAccountIdAndAcknowledgedAtIsNull(Long accountId);
}
