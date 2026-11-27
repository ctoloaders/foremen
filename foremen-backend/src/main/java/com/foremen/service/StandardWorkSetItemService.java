package com.foremen.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.StandardWorkSetItemDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.StandardWorkSetItemEntity;
import com.foremen.dao.model.WorkItemEntity;
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
    private final RoomTypeDao roomTypeDao;
    private final WorkItemDao workItemDao;
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

    /** A {@code (roomTypeId, workItemId)} cell of the standard-set matrix. */
    public record Pair(Long roomTypeId, Long workItemId) {}

    /** Result of {@link #applyMatrix}: rows really inserted / deleted and the full current set. */
    public record MatrixResult(int added, int removed, List<Pair> pairs) {}

    /**
     * Applies staged matrix edits atomically (one transaction). Idempotent: adding an existing pair or
     * removing a missing one is a no-op. Removals are applied before additions, so a pair listed in both
     * lists ends up present. Every id is validated first; one unknown room type or work rejects the whole
     * request with {@code 400 error.standard.work.set.matrix.unknown} and nothing is changed.
     */
    @Transactional
    public MatrixResult applyMatrix(List<Pair> add, List<Pair> remove) {
        Set<Pair> toAdd = distinct(add);
        Set<Pair> toRemove = distinct(remove);
        Set<Long> roomTypeIds = new LinkedHashSet<>();
        Set<Long> workIds = new LinkedHashSet<>();
        for (Set<Pair> side : List.of(toAdd, toRemove)) {
            for (Pair p : side) {
                roomTypeIds.add(p.roomTypeId());
                workIds.add(p.workItemId());
            }
        }
        for (Long id : roomTypeIds) {
            if (!roomTypeDao.existsById(id)) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.standard.work.set.matrix.unknown",
                        "roomTypeId=" + id);
            }
        }
        for (Long id : workIds) {
            if (!workItemDao.existsById(id)) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.standard.work.set.matrix.unknown",
                        "workItemId=" + id);
            }
        }
        int removed = 0;
        for (Pair p : toRemove) {
            var existing = dao.findByRoomTypeIdAndWorkItemId(p.roomTypeId(), p.workItemId());
            if (existing.isPresent()) {
                dao.delete(existing.get());
                removed++;
            }
        }
        entityManager.flush();
        int added = 0;
        for (Pair p : toAdd) {
            if (dao.existsByRoomTypeIdAndWorkItemId(p.roomTypeId(), p.workItemId())) {
                continue;
            }
            StandardWorkSetItemEntity row = new StandardWorkSetItemEntity();
            row.setRoomType(entityManager.getReference(RoomTypeEntity.class, p.roomTypeId()));
            row.setWorkItem(entityManager.getReference(WorkItemEntity.class, p.workItemId()));
            dao.save(row);
            added++;
        }
        entityManager.flush();
        List<Pair> pairs = new ArrayList<>();
        for (Object[] row : dao.findAllPairs()) {
            pairs.add(new Pair((Long) row[0], (Long) row[1]));
        }
        return new MatrixResult(added, removed, pairs);
    }

    private static Set<Pair> distinct(List<Pair> pairs) {
        Set<Pair> out = new LinkedHashSet<>();
        if (pairs != null) {
            for (Pair p : pairs) {
                if (p == null || p.roomTypeId() == null || p.workItemId() == null) {
                    throw new ForemenApiException(HttpStatus.BAD_REQUEST,
                            "error.standard.work.set.matrix.unknown", "null");
                }
                out.add(p);
            }
        }
        return out;
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
