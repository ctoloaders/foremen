package com.foremen.dao;

import com.foremen.dao.model.ExternalOrderDocumentEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** FOR-11-03 (R11.3, R14): documents attached to external orders. */
@Repository
public interface ExternalOrderDocumentDao extends AdminDao<ExternalOrderDocumentEntity, Long> {

    /** Attachments of one order with the document fetched, by id. */
    @Query("SELECT d FROM ExternalOrderDocumentEntity d JOIN FETCH d.procurementDocument "
            + "WHERE d.externalOrder.id = :orderId ORDER BY d.id")
    List<ExternalOrderDocumentEntity> findByExternalOrderId(@Param("orderId") Long orderId);

    /** Attachments of one document with the order fetched (all orders that reference it). */
    @Query("SELECT d FROM ExternalOrderDocumentEntity d JOIN FETCH d.externalOrder "
            + "WHERE d.procurementDocument.id = :documentId")
    List<ExternalOrderDocumentEntity> findByProcurementDocumentId(@Param("documentId") Long documentId);

    @Modifying
    @Query("DELETE FROM ExternalOrderDocumentEntity d WHERE d.externalOrder.id = :orderId")
    int deleteByExternalOrderId(@Param("orderId") Long orderId);
}
