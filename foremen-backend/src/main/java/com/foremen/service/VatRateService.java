package com.foremen.service;

import com.foremen.dao.VatRateDao;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.VatRateServiceExtendedModel;
import com.foremen.service.model.VatRateServiceModel;
import com.foremen.service.model.mapper.VatRateServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class VatRateService implements AdminService<
        VatRateServiceModel, VatRateServiceExtendedModel, VatRateEntity, Long> {

    private final VatRateDao dao;
    private final VatRateServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<VatRateEntity> daoModelClass = VatRateEntity.class;
}
