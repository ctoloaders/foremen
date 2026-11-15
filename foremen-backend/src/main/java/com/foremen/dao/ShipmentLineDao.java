package com.foremen.dao;

import com.foremen.dao.model.ShipmentLineEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/** FOR-11-04 (R2.2, R4.4): shipment lines. "Active" means the shipment is neither delivered nor cancelled. */
@Repository
public interface ShipmentLineDao extends AdminDao<ShipmentLineEntity, Long> {

    /** Lines of one shipment with the position fetched, by id. */
    @Query("SELECT l FROM ShipmentLineEntity l JOIN FETCH l.position WHERE l.shipment.id = :shipmentId ORDER BY l.id")
    List<ShipmentLineEntity> findByShipmentId(@Param("shipmentId") Long shipmentId);

    /**
     * Quantity already shipped on <b>non-cancelled</b> shipments other than {@code excludeShipmentId}
     * (R4.4, cap check): rows of {@code [positionId, sumQuantity]}; positions without lines are absent.
     * Pass {@code -1L} when no shipment is excluded (a new shipment).
     */
    @Query("SELECT l.position.id, SUM(l.quantity) FROM ShipmentLineEntity l "
            + "WHERE l.position.id IN :positionIds AND l.shipment.id <> :excludeShipmentId "
            + "AND l.shipment.status <> com.foremen.dao.model.ShipmentStatus.CANCELLED "
            + "GROUP BY l.position.id")
    List<Object[]> sumActiveQuantityByPositionExcluding(@Param("positionIds") Collection<Long> positionIds,
                                                        @Param("excludeShipmentId") Long excludeShipmentId);

    /** Quantity of one position on non-cancelled shipments; {@code 0} when none. */
    @Query("SELECT COALESCE(SUM(l.quantity), 0) FROM ShipmentLineEntity l WHERE l.position.id = :positionId "
            + "AND l.shipment.status <> com.foremen.dao.model.ShipmentStatus.CANCELLED")
    BigDecimal sumActiveQuantityByPositionId(@Param("positionId") Long positionId);

    /** Lines on Active_Shipments (not delivered, not cancelled) of the given positions, shipment fetched. */
    @Query("SELECT l FROM ShipmentLineEntity l JOIN FETCH l.shipment s WHERE l.position.id IN :positionIds "
            + "AND s.status NOT IN (com.foremen.dao.model.ShipmentStatus.DELIVERED, "
            + "com.foremen.dao.model.ShipmentStatus.CANCELLED) ORDER BY l.id")
    List<ShipmentLineEntity> findActiveByPositionIds(@Param("positionIds") Collection<Long> positionIds);

    /** True when the position sits on an Active_Shipment that has an ETA date (R11.1). */
    @Query("SELECT COUNT(l) > 0 FROM ShipmentLineEntity l WHERE l.position.id = :positionId "
            + "AND l.shipment.etaDate IS NOT NULL "
            + "AND l.shipment.status NOT IN (com.foremen.dao.model.ShipmentStatus.DELIVERED, "
            + "com.foremen.dao.model.ShipmentStatus.CANCELLED)")
    boolean existsActiveWithEta(@Param("positionId") Long positionId);

    /** True when the position sits on a DELIVERED shipment (R11.1: the planned date is then kept). */
    @Query("SELECT COUNT(l) > 0 FROM ShipmentLineEntity l WHERE l.position.id = :positionId "
            + "AND l.shipment.status = com.foremen.dao.model.ShipmentStatus.DELIVERED")
    boolean existsDeliveredByPositionId(@Param("positionId") Long positionId);

    /**
     * R4.3: quantity per position on non-cancelled shipments of one external order other than
     * {@code excludeShipmentId} (pass {@code -1L} for none): rows of {@code [positionId, sumQuantity]}.
     */
    @Query("SELECT l.position.id, SUM(l.quantity) FROM ShipmentLineEntity l "
            + "WHERE l.shipment.externalOrder.id = :orderId AND l.position.id IN :positionIds "
            + "AND l.shipment.id <> :excludeShipmentId "
            + "AND l.shipment.status <> com.foremen.dao.model.ShipmentStatus.CANCELLED "
            + "GROUP BY l.position.id")
    List<Object[]> sumActiveQuantityByOrderExcluding(@Param("orderId") Long orderId,
                                                     @Param("positionIds") Collection<Long> positionIds,
                                                     @Param("excludeShipmentId") Long excludeShipmentId);

    @Modifying
    @Query("DELETE FROM ShipmentLineEntity l WHERE l.shipment.id = :shipmentId")
    int deleteByShipmentId(@Param("shipmentId") Long shipmentId);
}
