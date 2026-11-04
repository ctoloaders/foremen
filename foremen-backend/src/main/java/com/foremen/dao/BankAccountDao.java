package com.foremen.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;

/**
 * FOR-07-04 (Requirements 3.1, 3.7, 27.1, 27.2) — DAO for {@link BankAccountEntity} (BANK accounts and
 * CASH registers).
 */
@Repository
public interface BankAccountDao extends AdminDao<BankAccountEntity, Long> {

    /** The BANK account for a provider account id within a connection (ingest upsert key). */
    Optional<BankAccountEntity> findByConnectionIdAndExternalId(Long connectionId, String externalId);

    /** All accounts of a connection, oldest first. */
    List<BankAccountEntity> findByConnectionIdOrderByIdAsc(Long connectionId);

    /** Accounts with the given normalized IBAN (internal-transfer detection). */
    List<BankAccountEntity> findByIban(String iban);

    /** Accounts of a type, oldest first (e.g. all CASH registers). */
    List<BankAccountEntity> findByTypeOrderByIdAsc(BankAccountType type);

    /**
     * The default, non-archived CASH register for a currency (at most one by the partial UNIQUE
     * index) — the target of SYSTEM cash lines.
     */
    @Query("SELECT a FROM BankAccountEntity a "
            + "WHERE a.type = com.foremen.dao.model.BankAccountType.CASH "
            + "AND a.defaultForCash = true AND a.archived = false AND a.currency = :currency")
    Optional<BankAccountEntity> findDefaultCashAccount(@Param("currency") String currency);
}
