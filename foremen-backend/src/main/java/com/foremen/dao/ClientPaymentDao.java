package com.foremen.dao;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.ClientPaymentEntity;

/** FOR-11-06 (R1.1): DAO of the unified client payment base row. */
@Repository
public interface ClientPaymentDao extends AdminDao<ClientPaymentEntity, Long> {

    /** The unified payment id of a materials proforma (the id the {@code /api/client-payments/{id}} paths take). */
    @Query("SELECT p.payment.id FROM ClientProformaEntity p WHERE p.id = :proformaId")
    Long findPaymentIdByProformaId(@Param("proformaId") Long proformaId);
}
