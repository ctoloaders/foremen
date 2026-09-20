package com.foremen.service;

import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.MaterialSellerServiceExtendedModel;
import com.foremen.service.model.MaterialSellerServiceModel;
import com.foremen.service.model.mapper.MaterialSellerServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class MaterialSellerService implements AdminService<
        MaterialSellerServiceModel, MaterialSellerServiceExtendedModel, MaterialSellerEntity, Long> {

    private final MaterialSellerDao dao;
    private final MaterialSellerServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<MaterialSellerEntity> daoModelClass = MaterialSellerEntity.class;
}
