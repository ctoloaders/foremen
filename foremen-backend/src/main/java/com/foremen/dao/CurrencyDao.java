package com.foremen.dao;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.CurrencyEntity;

@Repository
public interface CurrencyDao extends AdminDao<CurrencyEntity, Long> {

    /** Resolves a seeded currency by its unique {@code code} (e.g. {@code "PLN"}). */
    Optional<CurrencyEntity> findByCode(String code);
}
