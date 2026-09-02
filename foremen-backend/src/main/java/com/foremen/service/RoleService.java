package com.foremen.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.foremen.controller.model.*;
import com.foremen.dao.OperationDao;
import com.foremen.dao.ResourceDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.RoleResourceDao;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.model.RoleServiceExtendedModel;
import com.foremen.service.model.RoleServiceModel;
import com.foremen.service.model.mapper.RoleServiceMapper;
import com.foremen.service.permission.PermissionCache;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Getter
public class RoleService implements AdminService<
        RoleServiceModel, RoleServiceExtendedModel, RoleEntity, Long> {

    private final RoleDao dao;
    private final RoleResourceDao roleResourceDao;
    private final ResourceDao resourceDao;
    private final OperationDao operationDao;
    private final RoleServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final PermissionCache permissionCache;
    private final Class<RoleEntity> daoModelClass = RoleEntity.class;

    @Override
    @Transactional
    public void deleteById(Long id) {
        RoleEntity role = dao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", id));
        if (role.isSystem()) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.role.system.cannot.delete", id);
        }
        // Capture the role code BEFORE deletion; the entity is no longer readable afterward
        // and the permission cache is keyed by role code (Requirements 9.2, 9.5).
        String code = role.getCode();
        saveAudit(role, null, "DELETE");
        dao.deleteById(id);
        entityManager.flush();
        permissionCache.invalidate(code);
    }

    @Override
    @Transactional
    public RoleServiceExtendedModel update(Long id, RoleServiceExtendedModel model) {
        // Delegate to the default AdminService update, then evict the affected role's cache
        // entry so the next evaluation reloads (Requirements 9.3, 9.5). updateAll delegates to
        // update, so it is covered too. RoleUpdateRequest exposes no code, so the affected code
        // equals the existing role's code, which is returned unchanged in the extended model.
        RoleServiceExtendedModel updated = AdminService.super.update(id, model);
        permissionCache.invalidate(updated.code());
        return updated;
    }

    @Override
    public void afterCreate(RoleEntity role) {
        // A newly created role starts with an empty matrix, but a previously-cached
        // empty/deny entry for a reused code could linger, so evict defensively so the
        // next evaluation reloads (Requirements 9.4, 9.5).
        permissionCache.invalidate(role.getCode());
    }

    @Override
    public void validateUpdate(RoleEntity existing, RoleServiceExtendedModel update) {
        if (existing.isSystem()) {
            if (update.nameRU() != null && !update.nameRU().equals(existing.getNameRU())) {
                throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.role.system.name.immutable");
            }
            if (update.namePL() != null && !update.namePL().equals(existing.getNamePL())) {
                throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.role.system.name.immutable");
            }
        }
    }

    @Transactional(readOnly = true)
    public RolePermissionResponse getPermissions(Long roleId) {
        dao.findById(roleId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", roleId));

        List<RoleResourceEntity> roleResources = roleResourceDao.findAllByRoleId(roleId);

        List<PermissionEntryResponse> entries = roleResources.stream()
                .map(rr -> new PermissionEntryResponse(
                        rr.getResource().getId(),
                        rr.getResource().getCode(),
                        resolveResourceName(rr.getResource()),
                        rr.getOperations().stream()
                                .map(op -> new OperationInfo(op.getId(), op.getCode(), resolveOperationName(op)))
                                .toList()
                ))
                .toList();

        return new RolePermissionResponse(roleId, entries);
    }

    @Transactional
    public BatchRolePermissionResponse batchReplacePermissions(BatchRolePermissionRequest request) {
        List<RolePermissionResponse> results = new ArrayList<>();
        for (BatchRolePermissionEntry entry : request.entries()) {
            RolePermissionRequest singleRequest = new RolePermissionRequest(entry.permissions());
            results.add(replacePermissions(entry.roleId(), singleRequest));
        }
        return new BatchRolePermissionResponse(results);
    }

    @Transactional
    public RolePermissionResponse replacePermissions(Long roleId, RolePermissionRequest request) {
        RoleEntity role = dao.findById(roleId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", roleId));

        // Capture BEFORE snapshot from current permissions
        List<RoleResourceEntity> currentResources = roleResourceDao.findAllByRoleId(roleId);
        String beforeSnapshotJson = serializePermissionSnapshot(buildPermissionSnapshot(role, currentResources));

        roleResourceDao.deleteAllByRoleId(roleId);
        entityManager.flush();

        List<RoleResourceEntity> newEntries = new ArrayList<>();
        for (PermissionEntryRequest entry : request.permissions()) {
            ResourceEntity resource = resourceDao.findById(entry.resourceId())
                    .orElseThrow(() -> new ForemenApiException(HttpStatus.BAD_REQUEST,
                            "error.permission.resource.not.found", entry.resourceId()));

            RoleResourceEntity roleResource = new RoleResourceEntity();
            roleResource.setRole(role);
            roleResource.setResource(resource);

            if (entry.operationIds() != null && !entry.operationIds().isEmpty()) {
                List<OperationEntity> operations = new ArrayList<>();
                for (Long opId : entry.operationIds()) {
                    OperationEntity op = operationDao.findById(opId)
                            .orElseThrow(() -> new ForemenApiException(HttpStatus.BAD_REQUEST,
                                    "error.permission.operation.not.found", opId));
                    operations.add(op);
                }
                roleResource.setOperations(operations);
            }

            newEntries.add(roleResource);
        }

        roleResourceDao.saveAll(newEntries);
        entityManager.flush();

        // Capture AFTER snapshot from new permissions
        String afterSnapshotJson = serializePermissionSnapshot(buildPermissionSnapshot(role, newEntries));

        // Custom audit log for UPDATE_PERMISSIONS
        savePermissionAudit(role, beforeSnapshotJson, afterSnapshotJson);

        // Evict the permission cache entry for this role so the next evaluation reloads the
        // updated matrix (Requirements 9.1, 9.5). batchReplacePermissions inherits this because
        // it delegates to replacePermissions.
        permissionCache.invalidate(role.getCode());

        return getPermissions(roleId);
    }

    private String resolveResourceName(ResourceEntity resource) {
        Locale locale = LocaleContextHolder.getLocale();
        if (locale != null && "ru".equalsIgnoreCase(locale.getLanguage())) {
            return resource.getNameRU();
        }
        return resource.getNamePL();
    }

    private String resolveOperationName(OperationEntity operation) {
        Locale locale = LocaleContextHolder.getLocale();
        if (locale != null && "ru".equalsIgnoreCase(locale.getLanguage())) {
            return operation.getNameRU();
        }
        return operation.getNamePL();
    }

    private Map<String, Object> buildPermissionSnapshot(RoleEntity role, List<RoleResourceEntity> roleResources) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", role.getCode());

        roleResources.stream()
                .sorted((a, b) -> a.getResource().getCode().compareTo(b.getResource().getCode()))
                .forEach(rr -> {
                    List<String> opCodes = rr.getOperations().stream()
                            .map(OperationEntity::getCode)
                            .sorted()
                            .toList();
                    snapshot.put(rr.getResource().getCode(), opCodes);
                });

        return snapshot;
    }

    private String serializePermissionSnapshot(Map<String, Object> snapshot) {
        try {
            return AUDIT_OBJECT_MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization_failed\",\"class\":\"RoleEntity\"}";
        }
    }

    private void savePermissionAudit(RoleEntity role, String beforeSnapshot, String afterSnapshot) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String performedBy = (auth != null && auth.isAuthenticated()) ? auth.getName() : "SYSTEM";

        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.setEntityClass("RoleEntity");
        auditLog.setEntityId(role.getId());
        auditLog.setOperation("UPDATE_PERMISSIONS");
        auditLog.setPerformedBy(performedBy);
        auditLog.setPerformedAt(LocalDateTime.now());
        auditLog.setSnapshotBefore(beforeSnapshot);
        auditLog.setSnapshotAfter(afterSnapshot);
        auditLogDao.save(auditLog);
    }
}
