package com.foremen.service;

import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.OfferPackageServiceExtendedModel;
import com.foremen.service.model.OfferPackageServiceModel;
import com.foremen.service.model.mapper.OfferPackageServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class OfferPackageService implements AdminService<
        OfferPackageServiceModel, OfferPackageServiceExtendedModel, OfferPackageEntity, Long> {

    private final OfferPackageDao dao;
    private final OfferPackageServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<OfferPackageEntity> daoModelClass = OfferPackageEntity.class;
}
