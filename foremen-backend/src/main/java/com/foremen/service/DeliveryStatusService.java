package com.foremen.service;

import com.foremen.dao.DeliveryStatusDao;
import com.foremen.dao.model.DeliveryStatusEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.DeliveryStatusServiceExtendedModel;
import com.foremen.service.model.DeliveryStatusServiceModel;
import com.foremen.service.model.mapper.DeliveryStatusServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class DeliveryStatusService implements AdminService<
        DeliveryStatusServiceModel, DeliveryStatusServiceExtendedModel, DeliveryStatusEntity, Long> {

    private final DeliveryStatusDao dao;
    private final DeliveryStatusServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<DeliveryStatusEntity> daoModelClass = DeliveryStatusEntity.class;
}
