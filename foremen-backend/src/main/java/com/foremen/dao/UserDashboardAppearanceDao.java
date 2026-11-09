package com.foremen.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.UserDashboardAppearanceEntity;

/** FOR-10 (task 1.1) — DAO for {@link UserDashboardAppearanceEntity}; keyed by user id. */
@Repository
public interface UserDashboardAppearanceDao extends JpaRepository<UserDashboardAppearanceEntity, Long> {
}
