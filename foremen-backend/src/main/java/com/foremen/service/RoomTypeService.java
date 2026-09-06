package com.foremen.service;

import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.RoomTypeServiceExtendedModel;
import com.foremen.service.model.RoomTypeServiceModel;
import com.foremen.service.model.mapper.RoomTypeServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Getter
public class RoomTypeService implements AdminService<
        RoomTypeServiceModel, RoomTypeServiceExtendedModel, RoomTypeEntity, Long> {

    private final RoomTypeDao dao;
    private final RoomTypeServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<RoomTypeEntity> daoModelClass = RoomTypeEntity.class;
}
