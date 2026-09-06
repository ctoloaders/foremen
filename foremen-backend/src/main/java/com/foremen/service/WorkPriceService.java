package com.foremen.service;

import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.model.WorkPriceServiceModel;
import com.foremen.service.model.mapper.WorkPriceServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class WorkPriceService implements AdminService<
        WorkPriceServiceModel, WorkPriceServiceExtendedModel, WorkPriceEntity, Long> {

    private final WorkPriceDao dao;
    private final WorkPriceServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<WorkPriceEntity> daoModelClass = WorkPriceEntity.class;
}
