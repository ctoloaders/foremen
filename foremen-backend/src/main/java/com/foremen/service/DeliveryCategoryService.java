package com.foremen.service;

import com.foremen.dao.DeliveryCategoryDao;
import com.foremen.dao.model.DeliveryCategoryEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.DeliveryCategoryServiceExtendedModel;
import com.foremen.service.model.DeliveryCategoryServiceModel;
import com.foremen.service.model.mapper.DeliveryCategoryServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class DeliveryCategoryService implements AdminService<
        DeliveryCategoryServiceModel, DeliveryCategoryServiceExtendedModel, DeliveryCategoryEntity, Long> {

    private final DeliveryCategoryDao dao;
    private final DeliveryCategoryServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<DeliveryCategoryEntity> daoModelClass = DeliveryCategoryEntity.class;
}
