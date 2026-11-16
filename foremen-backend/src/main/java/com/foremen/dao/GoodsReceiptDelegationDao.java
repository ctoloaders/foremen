package com.foremen.dao;

import com.foremen.dao.model.DelegationStatus;
import com.foremen.dao.model.GoodsReceiptDelegationEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** FOR-11-06 (R12): goods-receipt delegations. */
@Repository
public interface GoodsReceiptDelegationDao extends AdminDao<GoodsReceiptDelegationEntity, Long> {

    Optional<GoodsReceiptDelegationEntity> findByShipmentIdAndStatus(Long shipmentId, DelegationStatus status);

    @Query("SELECT d.shipmentId FROM GoodsReceiptDelegationEntity d "
            + "WHERE d.assignee.id = :userId AND d.status = com.foremen.dao.model.DelegationStatus.ACTIVE")
    List<Long> findActiveShipmentIdsByAssignee(@Param("userId") Long userId);

    @Query("SELECT d FROM GoodsReceiptDelegationEntity d "
            + "WHERE d.shipmentId IN :shipmentIds AND d.status = com.foremen.dao.model.DelegationStatus.ACTIVE")
    List<GoodsReceiptDelegationEntity> findActiveByShipmentIds(@Param("shipmentIds") Collection<Long> shipmentIds);
}
