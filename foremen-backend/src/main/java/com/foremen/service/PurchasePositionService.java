package com.foremen.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.DeliveryCategoryDao;
import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.PurchasePositionVariantDao;
import com.foremen.dao.VatRateDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.PurchasePositionServiceExtendedModel;
import com.foremen.service.model.PurchasePositionServiceModel;
import com.foremen.service.model.mapper.PurchasePositionServiceMapper;
import com.foremen.service.procurement.HideDeliveredFilter;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.position.PositionAudienceResolver;
import com.foremen.service.procurement.position.PositionStage;
import com.foremen.service.procurement.proforma.ProformaStatus;
import com.foremen.service.query.QueryToken;
import com.foremen.service.query.QueryTokenizer;

import jakarta.persistence.EntityManager;

/**
 * CRUD service for purchase positions (FOR-11-00 R7, R9, R10.6).
 *
 * <p>Scoping: {@link #getProjectIdPath()} is {@code "project.id"}; {@link #allowedProjectIds(Long)}
 * delegates to {@link ProcurementProjectAccess} (ADMIN bypass stays in
 * {@link ProjectScopedService#addRequiredQuery()}, BUYER sees every project).
 *
 * <p>Write rules: create requires an ACTIVE project the caller can access, {@code kind = FINISHING},
 * {@code quantity > 0}, non-negative prices and a material that is global or belongs to the same
 * project; the unit and the VAT rates (23 / 8) default when omitted. Update rejects system-owned
 * fields (400) and applies the stage / history locks of R9.5 (409). {@code status} is written only by
 * {@code PositionLifecycleService}. Delete is limited to PLANNED / CANCELLED and MANAGER / ADMIN.
 *
 * <p>Money_Field filter and sort by a caller that is not {@code STAFF_FULL} is rejected with 400
 * {@code error.purchasePosition.field.forbidden}, so hidden values cannot be inferred (R10.6).
 */
@Service
public class PurchasePositionService implements ProjectScopedService<PurchasePositionServiceModel,
        PurchasePositionServiceExtendedModel, PurchasePositionEntity, Long> {

    static final String ENTITY_NOT_FOUND = "error.entity.not.found";
    static final String NOT_FOUND = "error.purchasePosition.notFound";
    static final String KIND_NOT_SUPPORTED = "error.purchasePosition.kindNotSupported";
    static final String PROJECT_NOT_ACTIVE = "error.purchasePosition.project.notActive";
    static final String QUANTITY_INVALID = "error.purchasePosition.quantity.invalid";
    static final String PRICE_INVALID = "error.purchasePosition.price.invalid";
    static final String MATERIAL_PROJECT_MISMATCH = "error.purchasePosition.material.projectMismatch";
    static final String FIELD_READ_ONLY = "error.purchasePosition.field.readOnly";
    static final String FIELD_FORBIDDEN = "error.purchasePosition.field.forbidden";
    static final String DELETE_NOT_ALLOWED = "error.purchasePosition.delete.notAllowed";
    static final String WORK_CATEGORY_INVALID = "error.purchasePosition.workCategory.invalid";
    static final String ACCESS_DENIED = "error.access.denied";
    /** FOR-11-03 (R4.6): the position sits on an issued proforma and its commercial fields are frozen. */
    static final String PROFORMA_POSITION_LOCKED = "error.proforma.position.locked";

    /** Default VAT rates (R7.7): purchase side 23 %, sale side 8 %. */
    private static final BigDecimal DEFAULT_PURCHASE_VAT = new BigDecimal("23");
    private static final BigDecimal DEFAULT_SALE_VAT = new BigDecimal("8");

    /** The six Money_Fields (entity property names; first path segment is compared). */
    static final Set<String> MONEY_FIELDS = Set.of(
            "plannedPurchasePrice", "actualPurchasePrice", "purchaseVatRate",
            "salePrice", "saleVatRate", "deliveryCostShare");

    /** Properties that {@code DELETE /{id}/property} may clear. Everything else is structural or system-owned. */
    private static final Set<String> CLEARABLE_PROPERTIES = Set.of(
            "notes", "blockNo", "deliveryCategory", "needByDate", "orderByDate", "decisionDeadline",
            "actualMaterial", "actualPurchasePrice", "plannedPurchasePrice", "salePrice",
            "purchaseVatRate", "saleVatRate", "estimateReference", "workCategory");

    /** Clearable properties frozen once the position is on an issued proforma (FOR-11-03 R4.6). */
    private static final Set<String> FROZEN_BY_PROFORMA_PROPERTIES = Set.of(
            "salePrice", "saleVatRate", "actualMaterial");

    private final PurchasePositionDao dao;
    private final PurchasePositionServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ProcurementProjectAccess projectAccess;
    private final PositionAudienceResolver audienceResolver;
    private final ProjectDao projectDao;
    private final FinishingMaterialDao materialDao;
    private final MeasurementUnitDao unitDao;
    private final DeliveryCategoryDao deliveryCategoryDao;
    private final VatRateDao vatRateDao;
    private final PurchasePositionVariantDao variantDao;
    private final WorkCategoryDao workCategoryDao;
    private final ClientProformaLineDao proformaLineDao;

    public PurchasePositionService(PurchasePositionDao dao,
                                   PurchasePositionServiceMapper mapper,
                                   AuditLogDao auditLogDao,
                                   EntityManager entityManager,
                                   ProcurementProjectAccess projectAccess,
                                   PositionAudienceResolver audienceResolver,
                                   ProjectDao projectDao,
                                   FinishingMaterialDao materialDao,
                                   MeasurementUnitDao unitDao,
                                   DeliveryCategoryDao deliveryCategoryDao,
                                   VatRateDao vatRateDao,
                                   PurchasePositionVariantDao variantDao,
                                   WorkCategoryDao workCategoryDao,
                                   ClientProformaLineDao proformaLineDao) {
        this.dao = dao;
        this.mapper = mapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.projectAccess = projectAccess;
        this.audienceResolver = audienceResolver;
        this.projectDao = projectDao;
        this.materialDao = materialDao;
        this.unitDao = unitDao;
        this.deliveryCategoryDao = deliveryCategoryDao;
        this.vatRateDao = vatRateDao;
        this.variantDao = variantDao;
        this.workCategoryDao = workCategoryDao;
        this.proformaLineDao = proformaLineDao;
    }

    // --- CRUD plumbing ---

    @Override
    public PurchasePositionDao getDao() {
        return dao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<PurchasePositionEntity, PurchasePositionServiceModel,
            PurchasePositionServiceExtendedModel> getMapper() {
        return mapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<PurchasePositionEntity> getDaoModelClass() {
        return PurchasePositionEntity.class;
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

    // --- variantCount (R21.2): one batched count query per page / read, no N+1 ---

    @Override
    public Page<PurchasePositionServiceModel> find(Pageable pageable, String rawQuery) {
        Page<PurchasePositionServiceModel> page = ProjectScopedService.super.find(pageable, rawQuery);
        fillVariantCounts(page.getContent());
        return page;
    }

    @Override
    public Page<PurchasePositionServiceExtendedModel> findExtended(Pageable pageable, String rawQuery) {
        Page<PurchasePositionServiceExtendedModel> page = ProjectScopedService.super.findExtended(pageable, rawQuery);
        fillVariantCounts(page.getContent());
        return page;
    }

    @Override
    public PurchasePositionServiceExtendedModel findById(Long id) {
        PurchasePositionServiceExtendedModel model = ProjectScopedService.super.findById(id);
        fillVariantCounts(java.util.Collections.singletonList(model));
        return model;
    }

    @Override
    public PurchasePositionServiceExtendedModel update(Long id, PurchasePositionServiceExtendedModel model) {
        PurchasePositionServiceExtendedModel result = ProjectScopedService.super.update(id, model);
        fillVariantCounts(java.util.Collections.singletonList(result));
        return result;
    }

    /** Sets {@code variantCount} on every model with a single batched count query (null entries skipped). */
    public void fillVariantCounts(java.util.Collection<? extends PurchasePositionServiceModel> models) {
        Map<Long, PurchasePositionServiceModel> byId = new LinkedHashMap<>();
        for (PurchasePositionServiceModel m : models) {
            if (m != null && m.getId() != null) {
                m.setVariantCount(0);
                byId.put(m.getId(), m);
            }
        }
        if (byId.isEmpty()) {
            return;
        }
        for (Object[] row : variantDao.countByPositionIds(byId.keySet())) {
            PurchasePositionServiceModel m = byId.get((Long) row[0]);
            if (m != null && row[1] instanceof Number n) {
                m.setVariantCount(n.intValue());
            }
        }
    }

    // --- Default sort: needByDate ascending (nulls last), then id ---

    @Override
    public Sort getDefaultSort() {
        return Sort.by(Sort.Order.asc("needByDate").nullsLast(), Sort.Order.asc("id"));
    }

    // --- Money_Field filter / sort guard (R10.6) ---

    /** Composes the R22 {@code hideDelivered} filter with the user and scope specifications. */
    @Override
    public Specification<PurchasePositionEntity> buildFinalSpecification(String rawQuery) {
        Specification<PurchasePositionEntity> base = ProjectScopedService.super.buildFinalSpecification(rawQuery);
        return HideDeliveredFilter.requested()
                ? Specification.where(base).and(HideDeliveredFilter.positions())
                : base;
    }

    @Override
    public Specification<PurchasePositionEntity> parseSpecification(String rawQuery) {
        rejectMoneyFilter(rawQuery);
        return ProjectScopedService.super.parseSpecification(rawQuery);
    }

    @Override
    public Sort processSort(Sort sort) {
        rejectMoneySort(sort);
        return ProjectScopedService.super.processSort(sort);
    }

    private void rejectMoneyFilter(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank() || audienceResolver.isStaffFull()) {
            return;
        }
        for (QueryToken token : QueryTokenizer.tokenize(rawQuery)) {
            if (token instanceof QueryToken.Filter filter && isMoneyPath(filter.field())) {
                throw forbiddenField(filter.field());
            }
        }
    }

    private void rejectMoneySort(Sort sort) {
        if (sort == null || sort.isUnsorted() || audienceResolver.isStaffFull()) {
            return;
        }
        for (Sort.Order order : sort) {
            if (isMoneyPath(order.getProperty())) {
                throw forbiddenField(order.getProperty());
            }
        }
    }

    private static boolean isMoneyPath(String path) {
        if (path == null) {
            return false;
        }
        int dot = path.indexOf('.');
        return MONEY_FIELDS.contains(dot < 0 ? path : path.substring(0, dot));
    }

    private static ForemenApiException forbiddenField(String field) {
        return new ForemenApiException(HttpStatus.BAD_REQUEST, FIELD_FORBIDDEN)
                .withParams(Map.of("field", field));
    }

    // --- Create ---

    @Override
    public void validateCreate(PurchasePositionServiceExtendedModel model) {
        Long projectId = model.getProjectId();
        if (projectId == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, "projectId", null);
        }
        requireProjectAccess(projectId);
        ProjectEntity project = projectDao.findById(projectId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, "projectId", projectId));
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new ForemenApiException(HttpStatus.CONFLICT, PROJECT_NOT_ACTIVE);
        }
        if (model.getKind() == null) {
            model.setKind(PositionKind.FINISHING);
        }
        if (model.getKind() != PositionKind.FINISHING) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, KIND_NOT_SUPPORTED);
        }
        if (model.getSourcingPath() == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invalid.field.name", "sourcingPath");
        }
        requirePositiveQuantity(model.getQuantity());
        requireNonNegativePrices(model);

        FinishingMaterialEntity planned = requireMaterial(model.getPlannedMaterialId(), projectId);

        if (model.getUnitId() == null) {
            model.setUnitId(planned.getUnit() == null ? null : planned.getUnit().getId());
        }
        if (model.getUnitId() == null || unitDao.findById(model.getUnitId()).isEmpty()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, "unitId", model.getUnitId());
        }
        applyDefaultVatRates(model);
        requireVatRate(model.getPurchaseVatRateId(), "purchaseVatRateId");
        requireVatRate(model.getSaleVatRateId(), "saleVatRateId");
        requireDeliveryCategory(model.getDeliveryCategoryId());
        requireEstimateReference(model.getEstimateReferenceId());
        requireWorkCategory(model.getWorkCategoryId());
    }

    /** Bulk create bypasses the single-entity hook in {@link AdminService}; validate each row here. */
    @Override
    @Transactional
    public List<PurchasePositionServiceExtendedModel> create(List<PurchasePositionServiceExtendedModel> models) {
        models.forEach(this::validateCreate);
        return ProjectScopedService.super.create(models);
    }

    // --- Update ---

    @Override
    public void validateUpdate(PurchasePositionEntity existing, PurchasePositionServiceExtendedModel model) {
        rejectSuppliedSystemFields(model);
        Long existingProjectId = existing.getProject().getId();
        if (model.getProjectId() != null && !Objects.equals(model.getProjectId(), existingProjectId)) {
            throw readOnly("projectId", HttpStatus.BAD_REQUEST);
        }
        if (model.getKind() != null && model.getKind() != existing.getKind()) {
            throw readOnly("kind", HttpStatus.BAD_REQUEST);
        }

        PositionStatus status = existing.getStatus();
        boolean planningStage = status == PositionStatus.PLANNED || status == PositionStatus.PROPOSED;

        boolean materialChanged = model.getPlannedMaterialId() != null
                && !Objects.equals(model.getPlannedMaterialId(), existing.getPlannedMaterial().getId());
        boolean pathChanged = model.getSourcingPath() != null && model.getSourcingPath() != existing.getSourcingPath();
        boolean quantityChanged = model.getQuantity() != null
                && model.getQuantity().compareTo(existing.getQuantity()) != 0;
        rejectIfFrozenByProforma(existing, model, materialChanged, quantityChanged);
        if (!planningStage) {
            if (materialChanged) {
                throw readOnly("plannedMaterialId", HttpStatus.CONFLICT);
            }
            if (pathChanged) {
                throw readOnly("sourcingPath", HttpStatus.CONFLICT);
            }
            if (quantityChanged) {
                throw readOnly("quantity", HttpStatus.CONFLICT);
            }
        }
        if (!reachedBuyer(status)) {
            if (model.getActualMaterialId() != null) {
                throw readOnly("actualMaterialId", HttpStatus.CONFLICT);
            }
            if (model.getActualPurchasePrice() != null) {
                throw readOnly("actualPurchasePrice", HttpStatus.CONFLICT);
            }
        }
        boolean agreedPriceChanged = changed(model.getSalePrice(), existing.getSalePrice())
                || changed(model.getPlannedPurchasePrice(), existing.getPlannedPurchasePrice());
        if ((materialChanged || agreedPriceChanged) && hasVariants(existing.getId())) {
            // Once a position has a variant history the planned item and agreed prices move only
            // through the variant operations (R9.5 / R21).
            throw readOnly(materialChanged ? "plannedMaterialId" : "salePrice", HttpStatus.CONFLICT);
        }

        if (model.getQuantity() != null) {
            requirePositiveQuantity(model.getQuantity());
        }
        requireNonNegativePrices(model);
        if (materialChanged) {
            requireMaterial(model.getPlannedMaterialId(), existingProjectId);
        }
        if (model.getActualMaterialId() != null) {
            requireMaterial(model.getActualMaterialId(), existingProjectId);
        }
        if (model.getUnitId() != null && unitDao.findById(model.getUnitId()).isEmpty()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, "unitId", model.getUnitId());
        }
        requireVatRate(model.getPurchaseVatRateId(), "purchaseVatRateId");
        requireVatRate(model.getSaleVatRateId(), "saleVatRateId");
        requireDeliveryCategory(model.getDeliveryCategoryId());
        requireEstimateReference(model.getEstimateReferenceId());
        if (model.getWorkCategoryId() != null
                && !Objects.equals(model.getWorkCategoryId(),
                        existing.getWorkCategory() == null ? null : existing.getWorkCategory().getId())) {
            if (!beforeOrdered(status)) {
                throw readOnly("workCategoryId", HttpStatus.CONFLICT);
            }
            requireWorkCategory(model.getWorkCategoryId());
        }
    }

    /**
     * FOR-11-03 (R4.6): from ISSUED onward (an active line on a non-draft proforma) quantity, sale price,
     * sale VAT rate and the planned / actual material cannot change. A draft proforma locks nothing (R3.7).
     */
    private void rejectIfFrozenByProforma(PurchasePositionEntity existing,
                                          PurchasePositionServiceExtendedModel model,
                                          boolean materialChanged, boolean quantityChanged) {
        Long existingSaleVat = existing.getSaleVatRate() == null ? null : existing.getSaleVatRate().getId();
        Long existingActual = existing.getActualMaterial() == null ? null : existing.getActualMaterial().getId();
        boolean touchesFrozen = quantityChanged || materialChanged
                || changed(model.getSalePrice(), existing.getSalePrice())
                || (model.getSaleVatRateId() != null && !Objects.equals(model.getSaleVatRateId(), existingSaleVat))
                || (model.getActualMaterialId() != null && !Objects.equals(model.getActualMaterialId(), existingActual));
        if (touchesFrozen && isFrozenByProforma(existing.getId())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, PROFORMA_POSITION_LOCKED);
        }
    }

    /** True when the position has a live line on an issued (non-draft) proforma. */
    private boolean isFrozenByProforma(Long positionId) {
        if (positionId == null) {
            return false;
        }
        for (ClientProformaLineEntity line : proformaLineDao.findActiveByPositionId(positionId)) {
            if (line.getProforma().getStatus() != ProformaStatus.DRAFT) {
                return true;
            }
        }
        return false;
    }

    private void rejectSuppliedSystemFields(PurchasePositionServiceExtendedModel model) {
        if (model.getStatus() != null) {
            throw readOnly("status", HttpStatus.BAD_REQUEST);
        }
        if (model.getOrderedQuantity() != null) {
            throw readOnly("orderedQuantity", HttpStatus.BAD_REQUEST);
        }
        if (model.getAcceptedQuantity() != null) {
            throw readOnly("acceptedQuantity", HttpStatus.BAD_REQUEST);
        }
        if (model.getDeliveryCostShare() != null) {
            throw readOnly("deliveryCostShare", HttpStatus.BAD_REQUEST);
        }
        if (model.getPlannedDeliveryDate() != null) {
            throw readOnly("plannedDeliveryDate", HttpStatus.BAD_REQUEST);
        }
    }

    /** {@code actualMaterial} / {@code actualPurchasePrice} are writable from WITH_BUYER on (R9.5). */
    private static boolean reachedBuyer(PositionStatus status) {
        return status != PositionStatus.CANCELLED
                && PositionStage.rank(status) >= PositionStage.rank(PositionStatus.WITH_BUYER);
    }

    private static boolean changed(BigDecimal supplied, BigDecimal current) {
        if (supplied == null) {
            return false;
        }
        return current == null || supplied.compareTo(current) != 0;
    }

    private boolean hasVariants(Long positionId) {
        for (Object[] row : variantDao.countByPositionIds(List.of(positionId))) {
            if (row[1] instanceof Number n && n.longValue() > 0) {
                return true;
            }
        }
        return false;
    }

    private static ForemenApiException readOnly(String field, HttpStatus status) {
        return new ForemenApiException(status, FIELD_READ_ONLY).withParams(Map.of("field", field));
    }

    // --- Delete ---

    /**
     * Only MANAGER / ADMIN may delete, and only in PLANNED or CANCELLED. The scope check runs before
     * the stage check so an out-of-scope position is indistinguishable from a missing one.
     */
    @Override
    @Transactional
    public void deleteById(Long id) {
        if (!hasAnyAuthority("ADMIN", "MANAGER")) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, ACCESS_DENIED);
        }
        assertProjectAccess(id);
        PurchasePositionEntity entity = dao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, id));
        if (entity.getStatus() != PositionStatus.PLANNED && entity.getStatus() != PositionStatus.CANCELLED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, DELETE_NOT_ALLOWED);
        }
        ProjectScopedService.super.deleteById(id);
    }

    /** Batch delete would skip the stage rule; route each id through {@link #deleteById}. */
    @Override
    @Transactional
    public void deleteAll(List<Long> ids) {
        ids.forEach(this::deleteById);
    }

    /** Clearing is limited to optional, non-system-owned properties (no status / project / request detach). */
    @Override
    @Transactional
    public void setPropertiesToNull(Long id, Set<String> propertyNames) {
        for (String name : propertyNames) {
            if (!CLEARABLE_PROPERTIES.contains(name)) {
                throw readOnly(name, HttpStatus.BAD_REQUEST);
            }
        }
        if (propertyNames.stream().anyMatch(FROZEN_BY_PROFORMA_PROPERTIES::contains) && isFrozenByProforma(id)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, PROFORMA_POSITION_LOCKED);
        }
        if (propertyNames.contains("workCategory")) {
            // Reset to automatic follows the same stage lock as setting the override (R5.4).
            dao.findById(id).ifPresent(existing -> {
                if (!beforeOrdered(existing.getStatus())) {
                    throw readOnly("workCategoryId", HttpStatus.CONFLICT);
                }
            });
        }
        ProjectScopedService.super.setPropertiesToNull(id, propertyNames);
    }

    // --- Validation helpers ---

    private static void requirePositiveQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, QUANTITY_INVALID);
        }
    }

    private static void requireNonNegativePrices(PurchasePositionServiceExtendedModel model) {
        for (BigDecimal price : new BigDecimal[] {
                model.getPlannedPurchasePrice(), model.getSalePrice(), model.getActualPurchasePrice()}) {
            if (price != null && price.signum() < 0) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, PRICE_INVALID);
            }
        }
    }

    /** The material must exist and be global or belong to {@code projectId} (R7.5). */
    private FinishingMaterialEntity requireMaterial(Long materialId, Long projectId) {
        if (materialId == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, "plannedMaterialId", null);
        }
        FinishingMaterialEntity material = materialDao.findById(materialId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, "materialId", materialId));
        if (material.getProject() != null && !Objects.equals(material.getProject().getId(), projectId)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MATERIAL_PROJECT_MISMATCH);
        }
        return material;
    }

    private void requireVatRate(Long id, String field) {
        if (id != null && vatRateDao.findById(id).isEmpty()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, field, id);
        }
    }

    private void requireDeliveryCategory(Long id) {
        if (id != null && deliveryCategoryDao.findById(id).isEmpty()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, "deliveryCategoryId", id);
        }
    }

    /** The category must exist and be active; it need not be present in the project schedule (R5.4). */
    private void requireWorkCategory(Long id) {
        if (id == null) {
            return;
        }
        WorkCategoryEntity category = workCategoryDao.findById(id).orElse(null);
        if (category == null || !category.isActive()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, WORK_CATEGORY_INVALID);
        }
    }

    /** The work-category link is editable only while the position is before ORDERED (and not cancelled). */
    private static boolean beforeOrdered(PositionStatus status) {
        return status != PositionStatus.CANCELLED
                && PositionStage.rank(status) < PositionStage.rank(PositionStatus.ORDERED);
    }

    private void requireEstimateReference(Long id) {
        if (id != null && entityManager.find(EstimateLineRoomMaterialEntity.class, id) == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, "estimateReferenceId", id);
        }
    }

    /** R7.7: fill omitted VAT rates with the dictionary entries of 23 % (purchase) and 8 % (sale), if present. */
    private void applyDefaultVatRates(PurchasePositionServiceExtendedModel model) {
        if (model.getPurchaseVatRateId() != null && model.getSaleVatRateId() != null) {
            return;
        }
        List<VatRateEntity> rates = new ArrayList<>();
        vatRateDao.findAll().forEach(rates::add);
        if (model.getPurchaseVatRateId() == null) {
            model.setPurchaseVatRateId(findRateId(rates, DEFAULT_PURCHASE_VAT));
        }
        if (model.getSaleVatRateId() == null) {
            model.setSaleVatRateId(findRateId(rates, DEFAULT_SALE_VAT));
        }
    }

    private static Long findRateId(List<VatRateEntity> rates, BigDecimal rate) {
        VatRateEntity inactiveMatch = null;
        for (VatRateEntity r : rates) {
            if (r.getRate() != null && r.getRate().compareTo(rate) == 0) {
                if (r.isActive()) {
                    return r.getId();
                }
                inactiveMatch = r;
            }
        }
        return inactiveMatch == null ? null : inactiveMatch.getId();
    }

    // --- Caller helpers ---

    /** Non-ADMIN callers may create only inside their allowed projects; denial is the usual 404. */
    private void requireProjectAccess(Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, projectId);
        }
        if (hasAnyAuthority("ADMIN")) {
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

    static boolean hasAnyAuthority(String... codes) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            for (String code : codes) {
                if (("ROLE_" + code).equals(a) || code.equals(a)) {
                    return true;
                }
            }
        }
        return false;
    }

    // --- Audit snapshot: flat, cycle-free ---

    @Override
    public String serializeEntity(PurchasePositionEntity e) {
        if (e == null) {
            return null;
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", e.getId());
        snap.put("projectId", e.getProject() == null ? null : e.getProject().getId());
        snap.put("kind", e.getKind());
        snap.put("sourcingPath", e.getSourcingPath());
        snap.put("status", e.getStatus());
        snap.put("plannedMaterialId", e.getPlannedMaterial() == null ? null : e.getPlannedMaterial().getId());
        snap.put("actualMaterialId", e.getActualMaterial() == null ? null : e.getActualMaterial().getId());
        snap.put("deliveryCategoryId", e.getDeliveryCategory() == null ? null : e.getDeliveryCategory().getId());
        snap.put("blockNo", e.getBlockNo());
        snap.put("quantity", e.getQuantity());
        snap.put("unitId", e.getUnit() == null ? null : e.getUnit().getId());
        snap.put("orderedQuantity", e.getOrderedQuantity());
        snap.put("acceptedQuantity", e.getAcceptedQuantity());
        snap.put("plannedPurchasePrice", e.getPlannedPurchasePrice());
        snap.put("salePrice", e.getSalePrice());
        snap.put("actualPurchasePrice", e.getActualPurchasePrice());
        snap.put("purchaseVatRateId", e.getPurchaseVatRate() == null ? null : e.getPurchaseVatRate().getId());
        snap.put("saleVatRateId", e.getSaleVatRate() == null ? null : e.getSaleVatRate().getId());
        snap.put("deliveryCostShare", e.getDeliveryCostShare());
        snap.put("needByDate", e.getNeedByDate());
        snap.put("orderByDate", e.getOrderByDate());
        snap.put("decisionDeadline", e.getDecisionDeadline());
        snap.put("plannedDeliveryDate", e.getPlannedDeliveryDate());
        snap.put("purchaseRequestId", e.getPurchaseRequest() == null ? null : e.getPurchaseRequest().getId());
        snap.put("estimateReferenceId", e.getEstimateReference() == null ? null : e.getEstimateReference().getId());
        snap.put("workCategoryId", e.getWorkCategory() == null ? null : e.getWorkCategory().getId());
        snap.put("notes", e.getNotes());
        try {
            return AUDIT_OBJECT_MAPPER.writeValueAsString(snap);
        } catch (JsonProcessingException ex) {
            return "{\"error\":\"serialization_failed\",\"class\":\"PurchasePositionEntity\"}";
        }
    }
}
