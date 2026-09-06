package com.foremen.service;

import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.MeasurementUnitServiceExtendedModel;
import com.foremen.service.model.MeasurementUnitServiceModel;
import com.foremen.service.model.mapper.MeasurementUnitServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class MeasurementUnitService implements AdminService<
        MeasurementUnitServiceModel, MeasurementUnitServiceExtendedModel, MeasurementUnitEntity, Long> {

    private final MeasurementUnitDao dao;
    private final MeasurementUnitServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<MeasurementUnitEntity> daoModelClass = MeasurementUnitEntity.class;
}
