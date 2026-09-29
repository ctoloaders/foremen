package com.foremen.service;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.foremen.dao.AdminDao;
import com.foremen.dao.ReadOnlyAdminDao;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaUpdate;
import jakarta.persistence.criteria.Root;

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
        validateCreate(model);
        DaoModel entity = getMapper().toCreateDaoModel(model);
        entity = getWriteDao().save(entity);
        getEntityManager().flush();
        afterCreate(entity);
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

    /**
     * A single (id, model) pair for a heterogeneous batch update.
     *
     * @param id    the id of the row to update
     * @param model the per-row service-extended model carrying that row's new field values
     * @param <ID>  the id type
     * @param <M>   the model type
     */
    record IdModel<ID, M>(ID id, M model) {}

    /**
     * Heterogeneous batch update: applies a distinct model to each id in one transaction.
     *
     * <p>Each item is delegated to the single-row {@link #update(Object, Object)} so that validation
     * ({@code validateUpdate} → e.g. {@code RoomService.normalize}), the before-state snapshot,
     * {@code updateFields}, per-row audit, AND the project-scope guard (via
     * {@code ProjectScopedService.assertProjectAccess} reached through the overridden
     * {@code update(id, model)}) all run identically to a single-row update. The shared
     * {@link Transactional} makes the whole batch atomic: any per-row failure rolls back every row.
     *
     * <p>This intentionally delegates per row rather than issuing a raw {@code saveAll}, so the
     * per-row project-scope guard fires for every element.
     *
     * @param items the (id, model) pairs to apply, in order
     * @return the updated service-extended models, in the same order as {@code items}
     */
    @Transactional
    default List<ServiceExtendedModel> update(List<IdModel<ID, ServiceExtendedModel>> items) {
        return items.stream()
                .map(item -> update(item.id(), item.model()))
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

    default void validateCreate(ServiceExtendedModel model) {
        // No-op default — subclasses override for custom validation
    }

    /**
     * Post-create hook invoked inside the single-entity {@link #create(Object)} transaction,
     * after the entity has been saved and flushed (so its generated id is available) and
     * around the audit write. No-op by default — subclasses override for side effects such
     * as issuing invitations. A thrown exception rolls back the enclosing create transaction.
     *
     * @param entity the persisted entity
     */
    default void afterCreate(DaoModel entity) {
        // No-op default — subclasses override for post-create side effects
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
        auditLog.setSnapshotAfter(serializeUpdateAfterSnapshot(beforeSnapshot, after));

        getAuditLogDao().save(auditLog);
    }

    // --- Serialization ---

    /**
     * Serializes a single entity to a JSON snapshot for audit before/after fields. This is the
     * overridable seam for CREATE/DELETE and the UPDATE before-state: subclasses whose JPA graph is
     * cyclic (e.g. an entity holding a collection that back-references it) override this to build a
     * flat, cycle-free snapshot instead of serializing the whole entity graph.
     *
     * <p>Default body: {@code null} in yields {@code null} out; otherwise the shared audit mapper
     * serializes the entity, and any failure is captured as the
     * {@code {"error":"serialization_failed","class":...}} fallback rather than crashing the write.
     */
    default String serializeEntity(DaoModel entity) {
        if (entity == null) return null;
        try {
            JsonNode tree = AUDIT_OBJECT_MAPPER.valueToTree(entity);
            ObjectNode flat = AUDIT_OBJECT_MAPPER.createObjectNode();
            flatten(null, tree, flat);
            return AUDIT_OBJECT_MAPPER.writeValueAsString(flat);
        } catch (Exception e) {
            // If serialization fails, store a fallback message rather than crashing the operation
            return "{\"error\":\"serialization_failed\",\"class\":\"" + entity.getClass().getSimpleName() + "\"}";
        }
    }

    /**
     * Produces the UPDATE after-state snapshot, given the already-serialized before-state string and
     * the post-update entity. This is the overridable seam for the UPDATE flow only: the default body
     * simply serializes the after entity via {@link #serializeEntity(Object)}, so ordinary entities get
     * a full after snapshot. Subclasses override this to build a cross-state (before&harr;after) diff.
     *
     * <p>The before-state is passed as the captured serialized string (not a live entity) because the
     * update flow mutates {@code existing} in place, so the pre-state is only reliably available as the
     * string captured before the mapper applied its changes.
     *
     * @param beforeSnapshot the serialized before-state (the value stored in {@code snapshotBefore})
     * @param after          the entity after the update was applied
     */
    default String serializeUpdateAfterSnapshot(String beforeSnapshot, DaoModel after) {
        if (after == null) return null;
        try {
            // Produce the after-state through the SAME seam that produced beforeSnapshot
            // (serializeEntity, which may be overridden by a subclass and is already flat). Parsing
            // both sides from their JSON text keeps node types consistent, so equal values compare
            // equal regardless of int/long representation.
            String afterSnapshot = serializeEntity(after);
            JsonNode afterParsed = afterSnapshot == null ? null : AUDIT_OBJECT_MAPPER.readTree(afterSnapshot);

            // beforeSnapshot was produced by serializeEntity (already flat). If it's missing
            // (should not happen on update), fall back to the full after-state.
            if (beforeSnapshot == null) {
                return afterSnapshot;
            }

            JsonNode beforeParsed = AUDIT_OBJECT_MAPPER.readTree(beforeSnapshot);
            if (!(beforeParsed instanceof ObjectNode beforeFlat) || !(afterParsed instanceof ObjectNode afterFlat)) {
                // Unexpected shape on either side (e.g. a fallback error string): emit the full
                // after-state rather than a partial/misleading diff.
                return afterSnapshot;
            }

            ObjectNode diff = flatDiff(beforeFlat, afterFlat);
            return AUDIT_OBJECT_MAPPER.writeValueAsString(diff);
        } catch (Exception e) {
            return "{\"error\":\"serialization_failed\",\"class\":\"" + after.getClass().getSimpleName() + "\"}";
        }
    }

    /**
     * Flattens a Jackson {@link JsonNode} tree into the single-level {@code target} object using
     * dot-notation for nested objects and index notation for arrays/collections.
     *
     * <ul>
     *   <li>Nested objects: {@code {"a":{"b":1}}} &rarr; {@code {"a.b":1}}.</li>
     *   <li>Object arrays: {@code {"users":[{"name":"x"}]}} &rarr; {@code {"users[0].name":"x"}}.</li>
     *   <li>Scalar arrays: {@code {"tags":["a","b"]}} &rarr; {@code {"tags[0]":"a","tags[1]":"b"}}.</li>
     *   <li>Leaf scalars (string/number/boolean/null) are kept as their JSON value.</li>
     *   <li>Empty objects/arrays are OMITTED (kept lean); a top-level empty entity yields {@code {}}.</li>
     * </ul>
     *
     * @param prefix the accumulated dotted/indexed key path so far ({@code null} at the root)
     * @param node   the current node being flattened
     * @param target the flat object node being built up
     */
    private static void flatten(String prefix, JsonNode node, ObjectNode target) {
        if (node == null || node.isNull()) {
            if (prefix != null) target.putNull(prefix);
            return;
        }
        if (node.isObject()) {
            if (node.isEmpty()) {
                // Empty object leaf: omit (lean). Root empty object -> target stays {}.
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                String key = prefix == null ? e.getKey() : prefix + "." + e.getKey();
                flatten(key, e.getValue(), target);
            }
            return;
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                // Empty array leaf: omit (lean).
                return;
            }
            for (int i = 0; i < node.size(); i++) {
                String key = (prefix == null ? "" : prefix) + "[" + i + "]";
                flatten(key, node.get(i), target);
            }
            return;
        }
        // Leaf scalar (string/number/boolean).
        if (prefix != null) {
            target.set(prefix, node);
        }
    }

    /**
     * Computes the flat diff between two already-flattened snapshot objects. The result contains
     * ONLY the keys whose value changed:
     * <ul>
     *   <li>present in {@code after} with a new/different value &rarr; included with the new value;</li>
     *   <li>present in {@code before} but absent from {@code after} (removed) &rarr; included as {@code null};</li>
     *   <li>present in both with an equal JSON value &rarr; omitted.</li>
     * </ul>
     */
    private static ObjectNode flatDiff(ObjectNode before, ObjectNode after) {
        ObjectNode diff = AUDIT_OBJECT_MAPPER.createObjectNode();

        Iterator<Map.Entry<String, JsonNode>> afterFields = after.fields();
        while (afterFields.hasNext()) {
            Map.Entry<String, JsonNode> e = afterFields.next();
            JsonNode beforeVal = before.get(e.getKey());
            if (beforeVal == null || !beforeVal.equals(e.getValue())) {
                diff.set(e.getKey(), e.getValue());
            }
        }

        Iterator<String> beforeNames = before.fieldNames();
        while (beforeNames.hasNext()) {
            String key = beforeNames.next();
            if (!after.has(key)) {
                diff.putNull(key);
            }
        }

        return diff;
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
