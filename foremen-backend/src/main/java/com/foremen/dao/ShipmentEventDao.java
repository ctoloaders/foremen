package com.foremen.dao;

import com.foremen.dao.model.ShipmentEventEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** FOR-11-04 (R2.3): append-only shipment status history. */
@Repository
public interface ShipmentEventDao extends AdminDao<ShipmentEventEntity, Long> {

    /** History of one shipment, oldest first. */
    @Query("SELECT e FROM ShipmentEventEntity e WHERE e.shipment.id = :shipmentId ORDER BY e.occurredAt, e.id")
    List<ShipmentEventEntity> findByShipmentId(@Param("shipmentId") Long shipmentId);
}
