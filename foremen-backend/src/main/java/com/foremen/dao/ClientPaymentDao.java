package com.foremen.dao;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.ClientPaymentEntity;

/** FOR-11-06 (R1.1): DAO of the unified client payment base row. */
@Repository
public interface ClientPaymentDao extends AdminDao<ClientPaymentEntity, Long> {
}
