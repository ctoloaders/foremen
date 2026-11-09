package com.foremen.dao;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.DashboardWidgetEntity;

/** FOR-10 (task 1.1) — DAO for {@link DashboardWidgetEntity}. */
@Repository
public interface DashboardWidgetDao extends AdminDao<DashboardWidgetEntity, Long> {

    Optional<DashboardWidgetEntity> findByCode(String code);

    boolean existsByCode(String code);
}
