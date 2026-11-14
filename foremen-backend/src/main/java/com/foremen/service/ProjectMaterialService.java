package com.foremen.service;

import java.util.Objects;
import java.util.Set;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.PurchasePositionVariantDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.ProjectMaterialServiceExtendedModel;
import com.foremen.service.model.ProjectMaterialServiceModel;
import com.foremen.service.model.mapper.ProjectMaterialServiceMapper;
import com.foremen.service.procurement.access.ProcurementProjectAccess;

import jakarta.persistence.EntityManager;

/**
 * CRUD service for Project_Materials (FOR-11-00 R3): {@link FinishingMaterialEntity} rows with
 * {@code project} set. The global {@link FinishingMaterialService} hides these rows; this service
 * shows only them.
 *
 * <p>Write validation is not duplicated: {@link #validateCreate}/{@link #validateUpdate} call
 * {@link FinishingMaterialService#validateCreate} (the {@link ProjectMaterialServiceExtendedModel}
 * is a finishing write model), then add the project rules: project required and {@code ACTIVE} on
 * create, project immutable on update, optional seller must exist. The audit snapshot is also
 * delegated to the finishing service so the flat shape is identical.
 *
 * <p>Scoping: {@link #getProjectIdPath()} is {@code "project.id"} and
 * {@link #allowedProjectIds(Long)} delegates to {@link ProcurementProjectAccess}. The ADMIN bypass of
 * {@link ProjectScopedService#addRequiredQuery()} would otherwise return global rows, so
 * {@link #addRequiredQuery()} additionally requires {@code project IS NOT NULL}; by-id read, update
 * and delete of a global row answer 404 {@code error.projectMaterial.notFound}.
 */
@Service
public class ProjectMaterialService implements ProjectScopedService<ProjectMaterialServiceModel,
        ProjectMaterialServiceExtendedModel, FinishingMaterialEntity, Long> {

    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";
    static final String NOT_FOUND_MESSAGE = "error.projectMaterial.notFound";
    static final String PROJECT_NOT_ACTIVE_MESSAGE = "error.projectMaterial.project.notActive";
    static final String PROJECT_IMMUTABLE_MESSAGE = "error.projectMaterial.project.immutable";
    static final String IN_USE_MESSAGE = "error.projectMaterial.inUse";

    private final FinishingMaterialDao dao;
    private final ProjectMaterialServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final FinishingMaterialService finishingMaterialService;
    private final ProcurementProjectAccess projectAccess;
    private final ProjectDao projectDao;
    private final MaterialSellerDao sellerDao;
    private final PurchasePositionDao purchasePositionDao;
    private final ImageStorage imageStorage;
    private final PurchasePositionVariantDao variantDao;

    public ProjectMaterialService(FinishingMaterialDao dao,
                                  ProjectMaterialServiceMapper mapper,
                                  AuditLogDao auditLogDao,
                                  EntityManager entityManager,
                                  FinishingMaterialService finishingMaterialService,
                                  ProcurementProjectAccess projectAccess,
                                  ProjectDao projectDao,
                                  MaterialSellerDao sellerDao,
                                  PurchasePositionDao purchasePositionDao,
                                  ImageStorage imageStorage,
                                  PurchasePositionVariantDao variantDao) {
        this.variantDao = variantDao;
        this.dao = dao;
        this.mapper = mapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.finishingMaterialService = finishingMaterialService;
        this.projectAccess = projectAccess;
        this.projectDao = projectDao;
        this.sellerDao = sellerDao;
        this.purchasePositionDao = purchasePositionDao;
        this.imageStorage = imageStorage;
    }

    // --- CRUD plumbing ---

    @Override
    public FinishingMaterialDao getDao() {
        return dao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<FinishingMaterialEntity, ProjectMaterialServiceModel,
            ProjectMaterialServiceExtendedModel> getMapper() {
        return mapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<FinishingMaterialEntity> getDaoModelClass() {
        return FinishingMaterialEntity.class;
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

    /** Project scope (non-ADMIN) AND {@code project IS NOT NULL} (also for the ADMIN bypass). */
    @Override
    public Specification<FinishingMaterialEntity> addRequiredQuery() {
        Specification<FinishingMaterialEntity> projectRowsOnly =
                (root, query, cb) -> cb.isNotNull(root.get("project"));
        Specification<FinishingMaterialEntity> scope = ProjectScopedService.super.addRequiredQuery();
        return scope == null ? projectRowsOnly : projectRowsOnly.and(scope);
    }

    // --- Validation ---

    @Override
    public void validateCreate(ProjectMaterialServiceExtendedModel model) {
        Long projectId = model.getProjectId();
        if (projectId == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "projectId", null);
        }
        ProjectEntity project = projectDao.findById(projectId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "projectId", projectId));
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new ForemenApiException(HttpStatus.CONFLICT, PROJECT_NOT_ACTIVE_MESSAGE);
        }
        validateSeller(model);
        finishingMaterialService.validateCreate(model);
    }

    @Override
    public void validateUpdate(FinishingMaterialEntity existing, ProjectMaterialServiceExtendedModel model) {
        if (existing.getProject() == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        if (model.getProjectId() != null && !Objects.equals(model.getProjectId(), existing.getProject().getId())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, PROJECT_IMMUTABLE_MESSAGE);
        }
        validateSeller(model);
        finishingMaterialService.validateUpdate(existing, model);
    }

    private void validateSeller(ProjectMaterialServiceExtendedModel model) {
        Long sellerId = model.getSellerId();
        if (sellerId != null && sellerDao.findById(sellerId).isEmpty()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "sellerId", sellerId);
        }
    }

    // --- By-id overrides: project rows only, in-use guard, photo cleanup ---

    @Override
    @Transactional
    public ProjectMaterialServiceExtendedModel findById(Long id) {
        requireProjectRow(id);
        return ProjectScopedService.super.findById(id);
    }

    @Override
    @Transactional
    public ProjectMaterialServiceExtendedModel update(Long id, ProjectMaterialServiceExtendedModel model) {
        FinishingMaterialEntity existing = getReadDao().findById(id).orElse(null);
        String previousKey = existing == null ? null : existing.getPhoto();

        ProjectMaterialServiceExtendedModel result = ProjectScopedService.super.update(id, model);

        if (previousKey != null && !Objects.equals(previousKey, model.getPhoto())) {
            imageStorage.deleteIfOrphan(previousKey);
        }
        return result;
    }

    /**
     * Rejects delete while a purchase position uses the material as planned or actual item. The
     * variants guard (position history rows) is added with task 13.1.
     */
    @Override
    @Transactional
    public void deleteById(Long id) {
        FinishingMaterialEntity existing = requireProjectRow(id);
        if (purchasePositionDao.existsByMaterialId(id) || variantDao.existsByMaterialId(id)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, IN_USE_MESSAGE);
        }
        String previousKey = existing.getPhoto();

        ProjectScopedService.super.deleteById(id);

        if (previousKey != null) {
            imageStorage.deleteIfOrphan(previousKey);
        }
    }

    /** Loads the row and requires it to be a Project_Material; a missing or global row is a 404. */
    private FinishingMaterialEntity requireProjectRow(Long id) {
        FinishingMaterialEntity entity = getReadDao().findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, id));
        if (entity.getProject() == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        return entity;
    }

    /** Same flat audit snapshot as the finishing material. */
    @Override
    public String serializeEntity(FinishingMaterialEntity entity) {
        return finishingMaterialService.serializeEntity(entity);
    }
}
