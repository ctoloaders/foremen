package com.foremen.service;

import com.foremen.dao.AdminDao;
import com.foremen.dao.ReadOnlyAdminDao;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaUpdate;
import jakarta.persistence.criteria.Root;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

public interface AdminService<ServiceModel, ServiceExtendedModel, DaoModel, ID>
        extends ReadOnlyAdminService<ServiceModel, ServiceExtendedModel, DaoModel, ID> {

    ObjectMapper AUDIT_OBJECT_MAPPER = createAuditObjectMapper();

    private static ObjectMapper createAuditObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        return mapper;
    }

    // --- Abstract methods ---

    AdminDao<DaoModel, ID> getDao();

    AuditLogDao getAuditLogDao();

    // --- DAO delegation ---

    default AdminDao<DaoModel, ID> getWriteDao() {
        return getDao();
    }

    @Override
    default ReadOnlyAdminDao<DaoModel, ID> getReadDao() {
        return getDao();
    }

    // --- Create ---

    @Transactional
    default ServiceExtendedModel create(ServiceExtendedModel model) {
        DaoModel entity = getMapper().toCreateDaoModel(model);
        entity = getWriteDao().save(entity);
        getEntityManager().flush();
        saveAudit(null, entity, "CREATE");
        return getMapper().toServiceExtendedModel(entity);
    }

    @Transactional
    default List<ServiceExtendedModel> create(List<ServiceExtendedModel> models) {
        List<DaoModel> entities = models.stream()
                .map(getMapper()::toCreateDaoModel)
                .toList();
        List<DaoModel> saved = (List<DaoModel>) getWriteDao().saveAll(entities);
        getEntityManager().flush();
        saved.forEach(entity -> saveAudit(null, entity, "CREATE"));
        return saved.stream()
                .map(getMapper()::toServiceExtendedModel)
                .toList();
    }

    // --- Update ---

    @Transactional
    default ServiceExtendedModel update(ID id, ServiceExtendedModel model) {
        DaoModel existing = getReadDao().findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", id));
        // Capture before-state snapshot BEFORE applying changes
        String beforeSnapshot = serializeEntity(existing);
        validateUpdate(existing, model);
        getMapper().updateFields(model, existing);
        DaoModel saved = getWriteDao().save(existing);
        getEntityManager().flush();
        saveAuditWithSnapshot(beforeSnapshot, saved, "UPDATE");
        return getMapper().toServiceExtendedModel(saved);
    }

    @Transactional
    default List<ServiceExtendedModel> updateAll(List<ID> ids, ServiceExtendedModel model) {
        return ids.stream()
                .map(id -> update(id, model))
                .toList();
    }

    @Transactional
    default <V> void updateSingleField(ID id, V value, BiConsumer<DaoModel, V> setter) {
        DaoModel existing = getReadDao().findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", id));
        String beforeSnapshot = serializeEntity(existing);
        setter.accept(existing, value);
        getWriteDao().save(existing);
        getEntityManager().flush();
        saveAuditWithSnapshot(beforeSnapshot, existing, "UPDATE");
    }

    default void validateUpdate(DaoModel existing, ServiceExtendedModel update) {
        // No-op default — subclasses override for custom validation
    }

    // --- Delete ---

    @Transactional
    default void deleteById(ID id) {
        DaoModel entity = getReadDao().findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", id));
        saveAudit(entity, null, "DELETE");
        getWriteDao().deleteById(id);
        getEntityManager().flush();
    }

    @Transactional
    default void deleteAll(List<ID> ids) {
        getReadDao().findAllByIdIn(ids).forEach(entity -> saveAudit(entity, null, "DELETE"));
        getWriteDao().deleteAllById(ids);
        getEntityManager().flush();
    }

    @Transactional
    @SuppressWarnings("unchecked")
    default void softDelete(String fieldName, Set<ID> ids) {
        // Capture before-state for each entity
        List<DaoModel> beforeEntities = (List<DaoModel>) getReadDao().findAllByIdIn(ids);
        beforeEntities.forEach(entity -> saveAudit(entity, null, "SOFT_DELETE"));

        CriteriaBuilder cb = getEntityManager().getCriteriaBuilder();
        CriteriaUpdate<DaoModel> cu = (CriteriaUpdate<DaoModel>) cb.createCriteriaUpdate(getDaoModelClass());
        Root<DaoModel> root = cu.from(getDaoModelClass());
        cu.set(fieldName, true);
        cu.where(root.get("id").in(ids));
        getEntityManager().createQuery(cu).executeUpdate();
        getEntityManager().flush();
    }

    @Transactional
    @SuppressWarnings("unchecked")
    default void setPropertiesToNull(ID id, Set<String> propertyNames) {
        Class<DaoModel> entityClass = getDaoModelClass();
        for (String propertyName : propertyNames) {
            if (!fieldExistsOnEntity(entityClass, propertyName)) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                        "error.invalid.field.name", propertyName);
            }
        }

        CriteriaBuilder cb = getEntityManager().getCriteriaBuilder();
        CriteriaUpdate<DaoModel> cu = (CriteriaUpdate<DaoModel>) cb.createCriteriaUpdate(entityClass);
        Root<DaoModel> root = cu.from(entityClass);

        for (String propertyName : propertyNames) {
            cu.set(root.get(propertyName), (Object) null);
        }

        cu.where(cb.equal(root.get("id"), id));
        getEntityManager().createQuery(cu).executeUpdate();
        getEntityManager().flush();
    }

    // --- Universal Audit ---

    /**
     * Saves an audit log entry with before/after entity snapshots serialized as JSON.
     *
     * @param before entity state before the operation (null for CREATE)
     * @param after  entity state after the operation (null for DELETE/SOFT_DELETE)
     * @param operation the operation type (CREATE, UPDATE, DELETE, SOFT_DELETE)
     */
    default void saveAudit(DaoModel before, DaoModel after, String operation) {
        DaoModel referenceEntity = after != null ? after : before;
        if (referenceEntity == null) return;

        Long entityId = extractEntityId(referenceEntity);
        String entityClassName = referenceEntity.getClass().getSimpleName();
        String performedBy = resolveCurrentUser();

        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.setEntityClass(entityClassName);
        auditLog.setEntityId(entityId);
        auditLog.setOperation(operation);
        auditLog.setPerformedBy(performedBy);
        auditLog.setPerformedAt(LocalDateTime.now());
        auditLog.setSnapshotBefore(serializeEntity(before));
        auditLog.setSnapshotAfter(serializeEntity(after));

        getAuditLogDao().save(auditLog);
    }

    /**
     * Overload for when before-snapshot is already serialized (used in update flow
     * where we serialize BEFORE applying mapper changes).
     */
    default void saveAuditWithSnapshot(String beforeSnapshot, DaoModel after, String operation) {
        if (after == null) return;

        Long entityId = extractEntityId(after);
        String entityClassName = after.getClass().getSimpleName();
        String performedBy = resolveCurrentUser();

        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.setEntityClass(entityClassName);
        auditLog.setEntityId(entityId);
        auditLog.setOperation(operation);
        auditLog.setPerformedBy(performedBy);
        auditLog.setPerformedAt(LocalDateTime.now());
        auditLog.setSnapshotBefore(beforeSnapshot);
        auditLog.setSnapshotAfter(serializeEntity(after));

        getAuditLogDao().save(auditLog);
    }

    // --- Serialization ---

    private String serializeEntity(DaoModel entity) {
        if (entity == null) return null;
        try {
            return AUDIT_OBJECT_MAPPER.writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            // If serialization fails, store a fallback message rather than crashing the operation
            return "{\"error\":\"serialization_failed\",\"class\":\"" + entity.getClass().getSimpleName() + "\"}";
        }
    }

    // --- Utility Methods ---

    private boolean fieldExistsOnEntity(Class<?> entityClass, String fieldName) {
        Class<?> current = entityClass;
        while (current != null && current != Object.class) {
            try {
                current.getDeclaredField(fieldName);
                return true;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return false;
    }

    private Long extractEntityId(Object entity) {
        try {
            Field idField = findField(entity.getClass(), "id");
            if (idField == null) {
                return null;
            }
            idField.setAccessible(true);
            Object id = idField.get(entity);
            return id instanceof Long longId ? longId : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private String resolveCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            return auth.getName();
        }
        return "SYSTEM";
    }
}
