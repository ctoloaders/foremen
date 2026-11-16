package com.foremen.service.procurement.proforma;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.ClientProformaServiceExtendedModel;
import com.foremen.service.model.ClientProformaServiceModel;
import com.foremen.service.model.mapper.ClientProformaServiceMapper;
import com.foremen.service.procurement.access.ProcurementProjectAccess;

import jakarta.persistence.EntityManager;

/**
 * Draft side of the client proforma (FOR-11-03 R3).
 *
 * <p>Scoping: {@code getProjectIdPath() = "project.id"}, {@link #allowedProjectIds(Long)} delegates to
 * {@link ProcurementProjectAccess}. Create, update and delete work on a {@code DRAFT} only; a draft
 * claims its positions through the partial unique index on active lines but changes no position status
 * (R3.7). Issue, cancel and payment are separate services.
 *
 * <p>A position is accepted onto a draft when it belongs to the project, is {@code AGREED},
 * {@code ACCEPTED}, has a sale price and sale VAT rate and sits on no other live proforma (R3.2). The
 * first offending position is reported with its id. Losing a race for a position surfaces as the
 * unique-index violation and is mapped to the same {@code alreadyInProforma} conflict.
 */
@Service
public class ClientProformaService implements ProjectScopedService<ClientProformaServiceModel,
        ClientProformaServiceExtendedModel, ClientProformaEntity, Long> {

    static final String ENTITY_NOT_FOUND = "error.entity.not.found";
    static final String POSITION_NOT_FOUND = "error.purchasePosition.notFound";
    static final String NOT_DRAFT = "error.proforma.notDraft";
    static final String PROJECT_NOT_ACTIVE = "error.proforma.project.notActive";
    static final String POSITIONS_INVALID = "error.proforma.positions.invalid";
    static final String FIELD_READ_ONLY = "error.proforma.field.readOnly";
    static final String POSITION_PROJECT_MISMATCH = "error.proforma.position.projectMismatch";
    static final String POSITION_NOT_AGREED = "error.proforma.position.notAgreed";
    static final String POSITION_NOT_ACCEPTED = "error.proforma.position.notAccepted";
    static final String POSITION_NO_SALE_PRICE = "error.proforma.position.noSalePrice";
    static final String POSITION_ALREADY_IN_PROFORMA = "error.proforma.position.alreadyInProforma";

    /** R3.1: 1..200 positions per proforma. */
    static final int MAX_POSITIONS = 200;

    /** Name of the partial unique index of R2.4 (changeset 236). */
    static final String ACTIVE_POSITION_INDEX = "uk_proforma_lines_position_active";

    private static final Set<String> CLEARABLE_PROPERTIES = Set.of("note", "dueDate");

    private final ClientProformaDao dao;
    private final ClientProformaLineDao lineDao;
    private final ClientProformaServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ProcurementProjectAccess projectAccess;
    private final ProjectDao projectDao;
    private final PurchasePositionDao positionDao;
    private final ProformaLineSnapshotBuilder snapshotBuilder;

    public ClientProformaService(ClientProformaDao dao,
                                 ClientProformaLineDao lineDao,
                                 ClientProformaServiceMapper mapper,
                                 AuditLogDao auditLogDao,
                                 EntityManager entityManager,
                                 ProcurementProjectAccess projectAccess,
                                 ProjectDao projectDao,
                                 PurchasePositionDao positionDao,
                                 ProformaLineSnapshotBuilder snapshotBuilder) {
        this.dao = dao;
        this.lineDao = lineDao;
        this.mapper = mapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.projectAccess = projectAccess;
        this.projectDao = projectDao;
        this.positionDao = positionDao;
        this.snapshotBuilder = snapshotBuilder;
    }

    // --- CRUD plumbing ---

    @Override
    public ClientProformaDao getDao() {
        return dao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<ClientProformaEntity, ClientProformaServiceModel,
            ClientProformaServiceExtendedModel> getMapper() {
        return mapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<ClientProformaEntity> getDaoModelClass() {
        return ClientProformaEntity.class;
    }

    @Override
    public Sort getDefaultSort() {
        return Sort.by(Sort.Order.desc("id"));
    }

    // --- Project scoping ---

    @Override
    public String getProjectIdPath() {
        return "project.id";
    }

    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccess.allowedProjectIds(userId);
    }

    /** The mapper reads the lazy project and the lines; keep one session for the whole read. */
    @Override
    @Transactional(readOnly = true)
    public ClientProformaServiceExtendedModel findById(Long id) {
        return ProjectScopedService.super.findById(id);
    }

    // --- Create (R3.1 - R3.4, R3.7) ---

    @Override
    @Transactional
    public ClientProformaServiceExtendedModel create(ClientProformaServiceExtendedModel model) {
        Long projectId = model.getProjectId();
        if (projectId == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, "projectId", null);
        }
        requireProjectAccess(projectId);
        List<PurchasePositionEntity> positions = validatePositions(projectId, model.getPositionIds(), null);

        ClientProformaEntity entity = dao.save(mapper.toCreateDaoModel(model));
        replaceLines(entity, positions, List.of());
        saveAudit(null, entity, "CREATE");
        return mapper.toServiceExtendedModel(entity);
    }

    /** Bulk create would bypass the per-proforma validation; route each model through {@link #create}. */
    @Override
    @Transactional
    public List<ClientProformaServiceExtendedModel> create(List<ClientProformaServiceExtendedModel> models) {
        return models.stream().map(this::create).toList();
    }

    // --- Update (R3.5) ---

    @Override
    @Transactional
    public ClientProformaServiceExtendedModel update(Long id, ClientProformaServiceExtendedModel model) {
        assertProjectAccess(id);
        ClientProformaEntity entity = load(id);
        requireDraft(entity);
        Long projectId = entity.getProject().getId();
        if (model.getProjectId() != null && !Objects.equals(model.getProjectId(), projectId)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, FIELD_READ_ONLY)
                    .withParams(Map.of("field", "projectId"));
        }
        String beforeSnapshot = serializeEntity(entity);

        mapper.updateFields(model, entity);
        if (model.getPositionIds() != null) {
            List<ClientProformaLineEntity> existing = lineDao.findByProformaIdOrderByLineNo(id);
            List<PurchasePositionEntity> positions = validatePositions(projectId, model.getPositionIds(), id);
            replaceLines(entity, positions, existing);
        } else {
            dao.save(entity);
            entityManager.flush();
        }
        saveAuditWithSnapshot(beforeSnapshot, entity, "UPDATE");
        return mapper.toServiceExtendedModel(entity);
    }

    /** Writes of single fields would skip the draft rule and the totals; not supported. */
    @Override
    @Transactional
    public <V> void updateSingleField(Long id, V value, java.util.function.BiConsumer<ClientProformaEntity, V> setter) {
        throw new ForemenApiException(HttpStatus.BAD_REQUEST, FIELD_READ_ONLY)
                .withParams(Map.of("field", "*"));
    }

    /** Only the optional {@code note} and {@code dueDate} may be cleared, and only on a draft. */
    @Override
    @Transactional
    public void setPropertiesToNull(Long id, Set<String> propertyNames) {
        for (String name : propertyNames) {
            if (!CLEARABLE_PROPERTIES.contains(name)) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, FIELD_READ_ONLY)
                        .withParams(Map.of("field", name));
            }
        }
        assertProjectAccess(id);
        requireDraft(load(id));
        ProjectScopedService.super.setPropertiesToNull(id, propertyNames);
    }

    // --- Delete (R3.6) ---

    /** Deletes a DRAFT; lines go with it, positions stay untouched. */
    @Override
    @Transactional
    public void deleteById(Long id) {
        assertProjectAccess(id);
        ClientProformaEntity entity = load(id);
        requireDraft(entity);
        lineDao.deleteAll(lineDao.findByProformaIdOrderByLineNo(id));
        entityManager.flush();
        ProjectScopedService.super.deleteById(id);
    }

    @Override
    @Transactional
    public void deleteAll(List<Long> ids) {
        ids.forEach(this::deleteById);
    }

    // --- Position validation (R3.2, R3.3) ---

    /**
     * Validates the requested positions and returns them in request order.
     *
     * @param proformaId the proforma being edited (its own live lines do not count as "already in a
     *                   proforma"), or {@code null} on create
     */
    List<PurchasePositionEntity> validatePositions(Long projectId, List<Long> positionIds, Long proformaId) {
        if (positionIds == null || positionIds.isEmpty() || positionIds.size() > MAX_POSITIONS
                || new HashSet<>(positionIds).size() != positionIds.size()
                || positionIds.stream().anyMatch(Objects::isNull)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, POSITIONS_INVALID);
        }
        ProjectEntity project = projectDao.findById(projectId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, "projectId", projectId));
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new ForemenApiException(HttpStatus.CONFLICT, PROJECT_NOT_ACTIVE);
        }

        Map<Long, PurchasePositionEntity> byId = new LinkedHashMap<>();
        positionDao.findAllByIdIn(positionIds).forEach(p -> byId.put(p.getId(), p));
        Set<Long> claimed = new HashSet<>(lineDao.findActivePositionIds(positionIds));
        if (proformaId != null) {
            lineDao.findByProformaIdOrderByLineNo(proformaId)
                    .forEach(l -> claimed.remove(l.getPosition().getId()));
        }

        List<PurchasePositionEntity> result = new ArrayList<>(positionIds.size());
        for (Long positionId : positionIds) {
            PurchasePositionEntity p = byId.get(positionId);
            if (p == null) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, POSITION_NOT_FOUND, positionId);
            }
            if (!Objects.equals(p.getProject().getId(), projectId)) {
                throw conflict(POSITION_PROJECT_MISMATCH, positionId);
            }
            if (p.getPlannedMaterial() == null) {
                throw conflict(com.foremen.service.procurement.PositionLabel.MATERIAL_REQUIRED, positionId);
            }
            if (p.getSourcingPath() != SourcingPath.AGREED) {
                throw conflict(POSITION_NOT_AGREED, positionId);
            }
            if (!isProformaEligible(p)) {
                throw conflict(POSITION_NOT_ACCEPTED, positionId);
            }
            if (p.getSalePrice() == null || p.getSaleVatRate() == null) {
                throw conflict(POSITION_NO_SALE_PRICE, positionId);
            }
            if (claimed.contains(positionId)) {
                throw conflict(POSITION_ALREADY_IN_PROFORMA, positionId);
            }
            result.add(p);
        }
        return result;
    }

    /**
     * FOR-11-06 (R10.5): an {@code ACCEPTED} position, or a buyer-added position still flagged
     * {@code client_unpaid} that already sits anywhere in {@code WITH_BUYER..RECEIVED}.
     */
    static boolean isProformaEligible(PurchasePositionEntity p) {
        if (p.getStatus() == PositionStatus.ACCEPTED) {
            return true;
        }
        return p.isClientUnpaid() && p.getStatus() != null
                && p.getStatus().ordinal() >= PositionStatus.WITH_BUYER.ordinal()
                && p.getStatus().ordinal() <= PositionStatus.RECEIVED.ordinal();
    }

    private static ForemenApiException conflict(String key, Long positionId) {
        return new ForemenApiException(HttpStatus.CONFLICT, key, positionId)
                .withParams(Map.of("positionId", positionId));
    }

    // --- Lines ---

    /**
     * Replaces the lines of a draft by a fresh snapshot of {@code positions} and refreshes the totals.
     * Old lines are removed first so the unique indexes never see both generations. The partial unique
     * index of R2.4 is the arbiter of concurrent claims; its violation becomes {@code alreadyInProforma}.
     */
    private void replaceLines(ClientProformaEntity entity, List<PurchasePositionEntity> positions,
                              List<ClientProformaLineEntity> existing) {
        try {
            if (!existing.isEmpty()) {
                lineDao.deleteAll(existing);
                entityManager.flush();
            }
            ProformaLineSnapshotBuilder.Snapshot snapshot =
                    snapshotBuilder.build(entity, positions, ProformaLineSnapshotBuilder.DEFAULT_LANGUAGE);
            entity.setTotalNet(snapshot.totals().net());
            entity.setTotalVat(snapshot.totals().vat());
            entity.setTotalGross(snapshot.totals().gross());
            dao.save(entity);
            lineDao.saveAll(snapshot.lines());
            entityManager.flush();
        } catch (RuntimeException e) {
            if (e instanceof ForemenApiException) {
                throw e;
            }
            if (isPositionClaimViolation(e)) {
                throw new ForemenApiException(HttpStatus.CONFLICT, POSITION_ALREADY_IN_PROFORMA);
            }
            throw e;
        }
    }

    /** True when the exception chain names the partial unique index of R2.4. */
    static boolean isPositionClaimViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains(ACTIVE_POSITION_INDEX)) {
                return true;
            }
        }
        return false;
    }

    // --- Helpers ---

    private ClientProformaEntity load(Long id) {
        return dao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, id));
    }

    private static void requireDraft(ClientProformaEntity entity) {
        if (entity.getStatus() != ProformaStatus.DRAFT) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_DRAFT);
        }
    }

    /** Non-ADMIN callers may create only inside their allowed projects; denial is the usual 404. */
    private void requireProjectAccess(Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, projectId);
        }
        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority()));
        if (admin) {
            return;
        }
        Long userId;
        try {
            userId = Long.parseLong(auth.getName().trim());
        } catch (RuntimeException e) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, projectId);
        }
        Set<Long> allowed = allowedProjectIds(userId);
        if (allowed == null || !allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, projectId);
        }
    }

    // --- Audit snapshot: flat, cycle-free ---

    @Override
    public String serializeEntity(ClientProformaEntity e) {
        if (e == null) {
            return null;
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", e.getId());
        snap.put("projectId", e.getProject() == null ? null : e.getProject().getId());
        snap.put("number", e.getNumber());
        snap.put("status", e.getStatus());
        snap.put("currency", e.getCurrency());
        snap.put("issueDate", e.getIssueDate());
        snap.put("dueDate", e.getDueDate());
        snap.put("note", e.getNote());
        snap.put("totalNet", e.getTotalNet());
        snap.put("totalVat", e.getTotalVat());
        snap.put("totalGross", e.getTotalGross());
        snap.put("paidGross", e.getPaidGross());
        snap.put("cancelReason", e.getCancelReason());
        try {
            return AUDIT_OBJECT_MAPPER.writeValueAsString(snap);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize proforma audit snapshot", ex);
        }
    }
}
