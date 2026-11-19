package com.foremen.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.foremen.dao.StandardWorkSetItemDao;
import com.foremen.dao.model.StandardWorkSetItemEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.StandardWorkSetItemServiceExtendedModel;
import com.foremen.service.model.StandardWorkSetItemServiceModel;
import com.foremen.service.model.mapper.StandardWorkSetItemServiceMapper;

import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * CRUD for the standard work set by room type (FOR-04-11 additional tasks 6-8). A GLOBAL admin
 * resource: it is NOT {@link ProjectScopedService} (the set is company-wide). Each
 * {@code (roomType, work)} pair may appear once; a duplicate is rejected with
 * {@code 409 error.standard.work.set.duplicate}.
 */
@Service
@RequiredArgsConstructor
@Getter
public class StandardWorkSetItemService implements AdminService<
        StandardWorkSetItemServiceModel, StandardWorkSetItemServiceExtendedModel,
        StandardWorkSetItemEntity, Long> {

    private final StandardWorkSetItemDao dao;
    private final StandardWorkSetItemServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<StandardWorkSetItemEntity> daoModelClass = StandardWorkSetItemEntity.class;

    @Override
    public void validateCreate(StandardWorkSetItemServiceExtendedModel model) {
        if (dao.existsByRoomTypeIdAndWorkItemId(model.roomTypeId(), model.workItemId())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.standard.work.set.duplicate");
        }
    }

    @Override
    public void validateUpdate(StandardWorkSetItemEntity existing, StandardWorkSetItemServiceExtendedModel update) {
        boolean samePair = existing.getRoomType().getId().equals(update.roomTypeId())
                && existing.getWorkItem().getId().equals(update.workItemId());
        if (!samePair && dao.existsByRoomTypeIdAndWorkItemId(update.roomTypeId(), update.workItemId())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.standard.work.set.duplicate");
        }
    }

    /** Flat audit snapshot (avoids serializing lazy associations). */
    @Override
    public String serializeEntity(StandardWorkSetItemEntity entity) {
        if (entity == null) {
            return null;
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", entity.getId());
        snap.put("roomTypeId", entity.getRoomType() != null ? entity.getRoomType().getId() : null);
        snap.put("workItemId", entity.getWorkItem() != null ? entity.getWorkItem().getId() : null);
        try {
            return AUDIT_OBJECT_MAPPER.writeValueAsString(snap);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization_failed\",\"class\":\"StandardWorkSetItemEntity\"}";
        }
    }
}
