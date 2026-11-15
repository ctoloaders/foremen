package com.foremen.dao;

import com.foremen.dao.model.GoodsReceiptEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * FOR-11-05 (R2.1, R2.4, R8.5, R8.7): receipt events. Every aggregate counts <b>non-voided</b> rows only.
 * The {@code Specification} support from {@link AdminDao} serves the filtered list.
 */
@Repository
public interface GoodsReceiptDao extends AdminDao<GoodsReceiptEntity, Long> {

    /**
     * Ledger sums per position: rows of {@code [positionId, kind (ReceiptKind), fromAccepted (Boolean),
     * sumQuantity]}; positions without events are absent.
     */
    @Query("SELECT g.position.id, g.kind, g.fromAccepted, SUM(g.quantity) FROM GoodsReceiptEntity g "
            + "WHERE g.position.id IN :positionIds AND g.voidedAt IS NULL "
            + "GROUP BY g.position.id, g.kind, g.fromAccepted")
    List<Object[]> sumsByPositionAndKind(@Param("positionIds") Collection<Long> positionIds);

    /**
     * Per shipment line sums: rows of {@code [shipmentId, positionId, kind, sumQuantity]} for events linked to
     * the given shipments, excluding returns of accepted units (they resolve nothing, design section 2).
     */
    @Query("SELECT g.shipment.id, g.position.id, g.kind, SUM(g.quantity) FROM GoodsReceiptEntity g "
            + "WHERE g.shipment.id IN :shipmentIds AND g.voidedAt IS NULL AND g.fromAccepted = false "
            + "GROUP BY g.shipment.id, g.position.id, g.kind")
    List<Object[]> sumsByShipmentAndPosition(@Param("shipmentIds") Collection<Long> shipmentIds);

    /**
     * Units released (MISSING plus door RETURNED) per position on non-cancelled shipments of one external order:
     * rows of {@code [positionId, sumQuantity]} (R8.5).
     */
    @Query("SELECT g.position.id, SUM(g.quantity) FROM GoodsReceiptEntity g "
            + "WHERE g.shipment.externalOrder.id = :orderId AND g.position.id IN :positionIds "
            + "AND g.voidedAt IS NULL AND g.fromAccepted = false "
            + "AND g.kind IN (com.foremen.dao.model.ReceiptKind.MISSING, com.foremen.dao.model.ReceiptKind.RETURNED) "
            + "GROUP BY g.position.id")
    List<Object[]> releasedByOrder(@Param("orderId") Long orderId,
                                   @Param("positionIds") Collection<Long> positionIds);

    /** R8.7: true when the position has at least one non-voided receipt event. */
    @Query("SELECT COUNT(g) > 0 FROM GoodsReceiptEntity g WHERE g.position.id = :positionId AND g.voidedAt IS NULL")
    boolean existsActiveByPositionId(@Param("positionId") Long positionId);

    /** True when the shipment has at least one non-voided linked receipt event (blocks cancel). */
    @Query("SELECT COUNT(g) > 0 FROM GoodsReceiptEntity g WHERE g.shipment.id = :shipmentId AND g.voidedAt IS NULL")
    boolean existsActiveByShipmentId(@Param("shipmentId") Long shipmentId);

    /** Idempotency lookup: every row stored for one client request id, ordered by part. */
    List<GoodsReceiptEntity> findByClientRequestIdOrderByPartNo(java.util.UUID clientRequestId);

    /** Non-voided and voided events of a position, oldest first. */
    List<GoodsReceiptEntity> findByPositionIdOrderByOccurredAtAscIdAsc(Long positionId);

    /**
     * FIFO allocation source: lines of the position on non-cancelled shipments ordered by ETA date (nulls last)
     * then shipment id; the shipment is fetched.
     */
    @Query("SELECT l FROM ShipmentLineEntity l JOIN FETCH l.shipment s WHERE l.position.id = :positionId "
            + "AND s.status <> com.foremen.dao.model.ShipmentStatus.CANCELLED "
            + "ORDER BY CASE WHEN s.etaDate IS NULL THEN 1 ELSE 0 END, s.etaDate, s.id")
    List<ShipmentLineEntity> findFifoLinesByPositionId(@Param("positionId") Long positionId);

    /** Lines of the positions on non-cancelled shipments (shipment fetched), for the to-receive view. */
    @Query("SELECT l FROM ShipmentLineEntity l JOIN FETCH l.shipment s WHERE l.position.id IN :positionIds "
            + "AND s.status <> com.foremen.dao.model.ShipmentStatus.CANCELLED ORDER BY s.id")
    List<ShipmentLineEntity> findNonCancelledLinesByPositionIds(@Param("positionIds") Collection<Long> positionIds);
}
