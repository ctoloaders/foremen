package com.foremen.dao;

import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.service.procurement.externalorder.ExternalOrderStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** FOR-11-03 (R11.1): external orders. */
@Repository
public interface ExternalOrderDao extends AdminDao<ExternalOrderEntity, Long> {

    /**
     * Ledger matching (R10.1) / search (R10.5): orders in the given statuses with the project and seller
     * fetched. The caller keeps {@code PAID} orders only when {@code paidSource = FINANCIER}.
     */
    @Query("SELECT o FROM ExternalOrderEntity o JOIN FETCH o.project JOIN FETCH o.seller "
            + "WHERE o.status IN :statuses ORDER BY o.id")
    List<ExternalOrderEntity> findByStatusInWithSeller(@Param("statuses") Collection<ExternalOrderStatus> statuses);

    /** Notifications (R19): assigned buyer id of the order; null when unassigned or unknown order. */
    @Query("SELECT b.id FROM ExternalOrderEntity o LEFT JOIN o.assignedBuyer b WHERE o.id = :id")
    Long findAssignedBuyerId(@Param("id") Long id);

    /** Notifications (R19): the audit {@code createdBy} (user e-mail) of the order; null when unknown. */
    @Query("SELECT o.createdBy FROM ExternalOrderEntity o WHERE o.id = :id")
    String findCreatedBy(@Param("id") Long id);
}
