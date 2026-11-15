package com.foremen.dao;

import com.foremen.dao.model.GoodsReceiptMediaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** FOR-11-05 (R2.2, R7): photos of receipt events. */
@Repository
public interface GoodsReceiptMediaDao extends AdminDao<GoodsReceiptMediaEntity, Long> {

    List<GoodsReceiptMediaEntity> findByReceiptIdOrderById(Long receiptId);

    long countByReceiptId(Long receiptId);

    Optional<GoodsReceiptMediaEntity> findByIdAndReceiptId(Long id, Long receiptId);

    /** Grouped counts {@code [receiptId, count]} for a set of receipts: one query, no N+1. */
    @Query("SELECT m.receipt.id, COUNT(m) FROM GoodsReceiptMediaEntity m WHERE m.receipt.id IN :receiptIds "
            + "GROUP BY m.receipt.id")
    List<Object[]> countGroupedByReceiptIds(@Param("receiptIds") Collection<Long> receiptIds);

    /** Media rows of a set of receipts, for list rendering. */
    @Query("SELECT m FROM GoodsReceiptMediaEntity m WHERE m.receipt.id IN :receiptIds ORDER BY m.id")
    List<GoodsReceiptMediaEntity> findByReceiptIds(@Param("receiptIds") Collection<Long> receiptIds);
}
