package com.foremen.dao;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.BankCounterpartyRuleEntity;

/**
 * FOR-07-04 (Requirement 17.3) — DAO for {@link BankCounterpartyRuleEntity}, UNIQUE by counterparty key.
 */
@Repository
public interface BankCounterpartyRuleDao extends AdminDao<BankCounterpartyRuleEntity, Long> {

    /** The rule for a counterparty key, enabled or not. */
    Optional<BankCounterpartyRuleEntity> findByCounterpartyKey(String counterpartyKey);

    /** The enabled rule for a counterparty key (detection). */
    Optional<BankCounterpartyRuleEntity> findByCounterpartyKeyAndEnabledTrue(String counterpartyKey);
}
