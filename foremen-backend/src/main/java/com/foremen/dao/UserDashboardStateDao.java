package com.foremen.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.UserDashboardStateEntity;

/** FOR-10 (task 1.1) — DAO for {@link UserDashboardStateEntity}; keyed by user id. */
@Repository
public interface UserDashboardStateDao extends JpaRepository<UserDashboardStateEntity, Long> {
}
