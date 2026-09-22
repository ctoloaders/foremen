package com.foremen.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.model.WorkPriceServiceModel;
import com.foremen.service.model.mapper.WorkPriceServiceMapper;

import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Work-prices catalog service. {@link WorkPriceEntity} is a single-price row (FOR-05-04, Requirement
 * 1): the row/paging unit reads directly with no per-package pivot and no MAX-fallback, so the
 * inherited {@link AdminService} read/write machinery is reused unchanged.
 */
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

    // ---------------------------------------------------------------------------------------------
    // Audit snapshot override.
    //
    // The generic AdminService audit path would serialize the whole JPA entity with a shared
    // ObjectMapper. WorkPriceEntity is a flat single-price row (FOR-05-04, R1.1), so this override is
    // kept only to preserve a stable, flat audit shape for existing audit-log consumers.
    //
    // Snapshot representation: a FLAT map used for EVERY state (create-after, delete-before,
    // update-before AND update-after):
    //
    //   { "id": 1, "workItemId": 42, "currencyCode": "PLN", "netPrice": 9.90 }
    // ---------------------------------------------------------------------------------------------

    /**
     * Flat single-entity snapshot seam (used for CREATE-after, DELETE-before, and both the before
     * and after of an UPDATE via the inherited {@code serializeUpdateAfterSnapshot} default).
     */
    @Override
    public String serializeEntity(WorkPriceEntity entity) {
        if (entity == null) {
            return null;
        }
        return serializeSnapshot(buildSnapshot(entity));
    }

    /**
     * Builds a flat snapshot of a {@link WorkPriceEntity}: {@code id}, {@code workItemId},
     * {@code currencyCode}, {@code netPrice}.
     */
    private Map<String, Object> buildSnapshot(WorkPriceEntity entity) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", entity.getId());
        snap.put("workItemId", entity.getWorkItem() != null ? entity.getWorkItem().getId() : null);
        snap.put("currencyCode", entity.getCurrency() != null ? entity.getCurrency().getCode() : null);
        snap.put("netPrice", entity.getNetPrice());
        return snap;
    }

    /**
     * Serializes a flat, cycle-free snapshot map with the shared audit mapper, mirroring the try/catch
     * fallback pattern used by {@code RoleService}. {@code null} in yields {@code null} out (so a
     * CREATE's before / a DELETE's after stay null).
     */
    private String serializeSnapshot(Map<String, Object> snap) {
        if (snap == null) {
            return null;
        }
        try {
            return AUDIT_OBJECT_MAPPER.writeValueAsString(snap);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization_failed\",\"class\":\"WorkPriceEntity\"}";
        }
    }
}
