package com.foremen.service;

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
import com.foremen.service.model.RoleServiceExtendedModel;
import com.foremen.service.model.RoleServiceModel;
import com.foremen.service.model.mapper.RoleServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private final Class<RoleEntity> daoModelClass = RoleEntity.class;

    @Override
    @Transactional
    public void deleteById(Long id) {
        RoleEntity role = dao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", id));
        if (role.isSystem()) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.role.system.cannot.delete", id);
        }
        saveAudit(role, null, "DELETE");
        dao.deleteById(id);
        entityManager.flush();
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

        saveAudit(role, role, "UPDATE_PERMISSIONS");

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
}
