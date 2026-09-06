package com.foremen.service;

import com.foremen.dao.CurrencyDao;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.CurrencyServiceExtendedModel;
import com.foremen.service.model.CurrencyServiceModel;
import com.foremen.service.model.mapper.CurrencyServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class CurrencyService implements AdminService<
        CurrencyServiceModel, CurrencyServiceExtendedModel, CurrencyEntity, Long> {

    private final CurrencyDao dao;
    private final CurrencyServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<CurrencyEntity> daoModelClass = CurrencyEntity.class;
}
