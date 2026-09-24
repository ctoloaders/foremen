package com.foremen.dao;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.MeasurementUnitEntity;

@Repository
public interface MeasurementUnitDao extends AdminDao<MeasurementUnitEntity, Long> {

    /** Resolves a seeded measurement unit by its unique {@code code} (e.g. {@code "m2"}). */
    Optional<MeasurementUnitEntity> findByCode(String code);
}
