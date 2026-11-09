package com.foremen.dao;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.RoleDefaultWidgetEntity;
import com.foremen.dao.model.RoleDefaultWidgetId;

/** FOR-10 (task 1.1) — DAO for {@link RoleDefaultWidgetEntity}. */
@Repository
public interface RoleDefaultWidgetDao extends JpaRepository<RoleDefaultWidgetEntity, RoleDefaultWidgetId> {

    @Query("SELECT d FROM RoleDefaultWidgetEntity d WHERE d.roleId = :roleId ORDER BY d.cellIndex")
    List<RoleDefaultWidgetEntity> findByRoleIdOrdered(@Param("roleId") Long roleId);
}
