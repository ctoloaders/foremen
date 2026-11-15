package com.foremen.dao;

import com.foremen.dao.model.ExternalOrderLineEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** FOR-11-03 (R11.2, R12.3, R13.2): external-order lines. */
@Repository
public interface ExternalOrderLineDao extends AdminDao<ExternalOrderLineEntity, Long> {

    /** Lines of one order with the position fetched, by id. */
    @Query("SELECT l FROM ExternalOrderLineEntity l JOIN FETCH l.position "
            + "WHERE l.externalOrder.id = :orderId ORDER BY l.id")
    List<ExternalOrderLineEntity> findByExternalOrderId(@Param("orderId") Long orderId);

    /**
     * Lines of many orders in ONE query with the purchase VAT rate fetched (R21.3: list totals without
     * N+1). The position is not fetched: totals need only price, quantity and rate.
     */
    @Query("SELECT l FROM ExternalOrderLineEntity l LEFT JOIN FETCH l.purchaseVatRate "
            + "WHERE l.externalOrder.id IN :orderIds ORDER BY l.id")
    List<ExternalOrderLineEntity> findForTotalsByExternalOrderIds(@Param("orderIds") Collection<Long> orderIds);

    /**
     * Coverage of positions by <b>non-cancelled</b> orders other than {@code excludeOrderId}: rows of
     * {@code [positionId, sumQuantity]}; positions without lines are absent. Pass {@code -1L} when no order
     * is excluded (a new order).
     */
    @Query("SELECT l.position.id, SUM(l.quantity) FROM ExternalOrderLineEntity l "
            + "WHERE l.position.id IN :positionIds AND l.externalOrder.id <> :excludeOrderId "
            + "AND l.externalOrder.status <> com.foremen.service.procurement.externalorder.ExternalOrderStatus.CANCELLED "
            + "GROUP BY l.position.id")
    List<Object[]> sumQuantityByPositionExcluding(@Param("positionIds") Collection<Long> positionIds,
                                                  @Param("excludeOrderId") Long excludeOrderId);

    /**
     * Lines of one position in <b>committed</b> orders ({@code SENT_FOR_PAYMENT}, {@code PAID}), with the
     * order fetched (R13.2, R17.1).
     */
    @Query("SELECT l FROM ExternalOrderLineEntity l JOIN FETCH l.externalOrder o "
            + "WHERE l.position.id = :positionId AND o.status IN ("
            + "com.foremen.service.procurement.externalorder.ExternalOrderStatus.SENT_FOR_PAYMENT, "
            + "com.foremen.service.procurement.externalorder.ExternalOrderStatus.PAID) ORDER BY l.id")
    List<ExternalOrderLineEntity> findCommittedByPositionId(@Param("positionId") Long positionId);

    /** True when the position sits on any non-cancelled order. */
    @Query("SELECT COUNT(l) > 0 FROM ExternalOrderLineEntity l WHERE l.position.id = :positionId "
            + "AND l.externalOrder.status <> com.foremen.service.procurement.externalorder.ExternalOrderStatus.CANCELLED")
    boolean existsLiveByPositionId(@Param("positionId") Long positionId);

    @Modifying
    @Query("DELETE FROM ExternalOrderLineEntity l WHERE l.externalOrder.id = :orderId")
    int deleteByExternalOrderId(@Param("orderId") Long orderId);
}
