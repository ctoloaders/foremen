package com.foremen.dao;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.UserDashboardWidgetEntity;

/** FOR-10 (task 1.1) — DAO for {@link UserDashboardWidgetEntity} (placements). */
@Repository
public interface UserDashboardWidgetDao extends AdminDao<UserDashboardWidgetEntity, Long> {

    @Query("SELECT p FROM UserDashboardWidgetEntity p WHERE p.user.id = :userId ORDER BY p.cellIndex")
    List<UserDashboardWidgetEntity> findByUserIdOrdered(@Param("userId") Long userId);

    @Query("SELECT p FROM UserDashboardWidgetEntity p WHERE p.widget.id = :widgetId")
    List<UserDashboardWidgetEntity> findByWidgetId(@Param("widgetId") Long widgetId);

    @Query("SELECT COUNT(p) > 0 FROM UserDashboardWidgetEntity p WHERE p.widget.id = :widgetId")
    boolean existsByWidgetId(@Param("widgetId") Long widgetId);
}
