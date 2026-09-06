package com.foremen.dao;

import com.foremen.dao.model.VatRateEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface VatRateDao extends AdminDao<VatRateEntity, Long> {
}
