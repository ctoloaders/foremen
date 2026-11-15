package com.foremen.dao;

import com.foremen.dao.model.ShipmentEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/** FOR-11-04 (R2.1): shipments. "Active" means neither {@code DELIVERED} nor {@code CANCELLED}. */
@Repository
public interface ShipmentDao extends AdminDao<ShipmentEntity, Long> {

    /**
     * Scheduler selection (design section 6): active {@code CARRIER} shipments whose next check is due
     * (a never-checked shipment, {@code next_check_at IS NULL}, is due too), never-checked first, then oldest due.
     */
    @Query("SELECT s FROM ShipmentEntity s WHERE s.deliveryMethod = com.foremen.dao.model.DeliveryMethod.CARRIER "
            + "AND s.status NOT IN (com.foremen.dao.model.ShipmentStatus.DELIVERED, "
            + "com.foremen.dao.model.ShipmentStatus.CANCELLED) "
            + "AND (s.nextCheckAt IS NULL OR s.nextCheckAt <= :now) "
            + "ORDER BY s.nextCheckAt ASC NULLS FIRST, s.id ASC")
    List<ShipmentEntity> findDueForTracking(@Param("now") OffsetDateTime now, Pageable pageable);

    /** Active shipments of one project that carry an ETA date. */
    @Query("SELECT s FROM ShipmentEntity s WHERE s.project.id = :projectId AND s.etaDate IS NOT NULL "
            + "AND s.status NOT IN (com.foremen.dao.model.ShipmentStatus.DELIVERED, "
            + "com.foremen.dao.model.ShipmentStatus.CANCELLED) ORDER BY s.etaDate, s.id")
    List<ShipmentEntity> findActiveWithEtaByProjectId(@Param("projectId") Long projectId);

    /**
     * Deadline sweep (design section 10): active shipments with an ETA date whose project has a works
     * schedule, with the project fetched.
     */
    @Query("SELECT s FROM ShipmentEntity s JOIN FETCH s.project p WHERE s.etaDate IS NOT NULL "
            + "AND s.status NOT IN (com.foremen.dao.model.ShipmentStatus.DELIVERED, "
            + "com.foremen.dao.model.ShipmentStatus.CANCELLED) "
            + "AND EXISTS (SELECT 1 FROM ProjectScheduleEntity ps WHERE ps.project.id = p.id) "
            + "ORDER BY s.id")
    List<ShipmentEntity> findActiveWithEtaForProjectsWithSchedule();
}
