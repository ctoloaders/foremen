package com.foremen.dao;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.PurchaseRequestEntity;

@Repository
public interface PurchaseRequestDao extends AdminDao<PurchaseRequestEntity, Long> {

    /**
     * Overdue-sweep candidates (R11): expected delivery date before {@code today} and no overdue
     * notification sent yet. Completed/cancelled requests are excluded later by the status deriver.
     */
    @Query("SELECT r FROM PurchaseRequestEntity r "
            + "WHERE r.expectedDeliveryDate < :today AND r.overdueNotifiedAt IS NULL ORDER BY r.id")
    List<PurchaseRequestEntity> findOverdueCandidates(@Param("today") LocalDate today);
}
