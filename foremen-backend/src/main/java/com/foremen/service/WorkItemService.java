package com.foremen.service;

import com.foremen.dao.WorkItemDao;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.WorkItemServiceExtendedModel;
import com.foremen.service.model.WorkItemServiceModel;
import com.foremen.service.model.mapper.WorkItemServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class WorkItemService implements AdminService<
        WorkItemServiceModel, WorkItemServiceExtendedModel, WorkItemEntity, Long> {

    private final WorkItemDao dao;
    private final WorkItemServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<WorkItemEntity> daoModelClass = WorkItemEntity.class;
}
