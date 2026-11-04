package com.foremen.dao;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.BankConnectionSecretEntity;

/**
 * FOR-07-04 (Requirement 2.1) — DAO for {@link BankConnectionSecretEntity}, keyed by the connection id
 * ({@code findById(connectionId)}). Rows are removed with their connection (ON DELETE CASCADE).
 */
@Repository
public interface BankConnectionSecretDao extends AdminDao<BankConnectionSecretEntity, Long> {
}
