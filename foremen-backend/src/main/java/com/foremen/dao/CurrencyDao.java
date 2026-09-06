package com.foremen.dao;

import com.foremen.dao.model.CurrencyEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface CurrencyDao extends AdminDao<CurrencyEntity, Long> {
}
