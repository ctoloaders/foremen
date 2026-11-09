package com.foremen.dao;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.UserDashboardWidgetStyleEntity;

/** FOR-10 (task 1.1) — DAO for {@link UserDashboardWidgetStyleEntity}; keyed by placement id. */
@Repository
public interface UserDashboardWidgetStyleDao extends JpaRepository<UserDashboardWidgetStyleEntity, Long> {

    List<UserDashboardWidgetStyleEntity> findByPlacementIdIn(Collection<Long> placementIds);
}
