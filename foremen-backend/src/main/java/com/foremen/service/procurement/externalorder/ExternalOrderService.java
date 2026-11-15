package com.foremen.service.procurement.externalorder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ExternalOrderDao;
import com.foremen.dao.ExternalOrderDocumentDao;
import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.PurchaseRequestDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.VatRateDao;
import com.foremen.dao.model.ExternalOrderDocumentEntity;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.ExternalOrderDocumentServiceModel;
import com.foremen.service.model.ExternalOrderLineServiceModel;
import com.foremen.service.model.ExternalOrderServiceExtendedModel;
import com.foremen.service.model.ExternalOrderServiceModel;
import com.foremen.service.model.mapper.ExternalOrderServiceMapper;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.planner.DeliveryCostAllocator;
import com.foremen.service.procurement.planner.PositionMoneyMath;

import jakarta.persistence.EntityManager;

/**
 * Draft side of the external order (FOR-11-03 R12, R13.1, R13.4, R14, R21.2): header CRUD,
 * {@link #replaceLines}, {@link #replaceDocuments} and delete of a draft. Sending, payment, return and
 * cancel are {@code ExternalOrderLifecycleService}; position stages are {@link PositionOrderSyncService}.
 * Nothing here changes a position (drafts do not change stages).
 *
 * <p>Scoping: {@code getProjectIdPath() = "project.id"}; {@link #allowedProjectIds(Long)} delegates to
 * {@link ProcurementProjectAccess}.
 *
 * <p>Lines are validated against the order's project, the position stage ({@code WITH_BUYER}) and path
 * ({@code AGREED} or {@code PACKAGE}), the quantity still uncovered by other non-cancelled orders and the
 * ceiling (sale net price). The delivery cost is re-allocated to the lines on every save of lines or of
 * {@code deliveryCostNet}. Audit snapshots hold no purchase prices (R11.7): only ids, status, dates,
 * references and the set of positions and documents.
 */
@Service
public class ExternalOrderService implements ProjectScopedService<ExternalOrderServiceModel,
        ExternalOrderServiceExtendedModel, ExternalOrderEntity, Long> {

    static final String ENTITY_NOT_FOUND = "error.entity.not.found";
    static final String POSITION_NOT_FOUND = "error.purchasePosition.notFound";
    static final String NOT_DRAFT = "error.externalOrder.notDraft";
    static final String FIELD_READ_ONLY = "error.externalOrder.field.readOnly";
    static final String BUYER_INVALID = "error.externalOrder.buyer.invalid";
    static final String REQUEST_PROJECT_MISMATCH = "error.externalOrder.request.projectMismatch";
    static final String DELIVERY_COST_INVALID = "error.externalOrder.deliveryCost.invalid";
    static final String DELIVERY_VAT_REQUIRED = "error.externalOrder.deliveryVat.required";
    static final String LINES_INVALID = "error.externalOrder.lines.invalid";
    static final String LINE_PROJECT_MISMATCH = "error.externalOrder.line.projectMismatch";
    static final String POSITION_NOT_PURCHASABLE = "error.externalOrder.position.notPurchasable";
    static final String ABOVE_CEILING = "error.externalOrder.line.aboveCeiling";
    static final String DOCUMENTS_INVALID = "error.externalOrder.documents.invalid";
    static final String DOCUMENTS_LOCKED = "error.externalOrder.documents.locked";
    static final String DOCUMENTS_OVER_ATTRIBUTED = "error.externalOrder.documents.overAttributed";

    /** The VAT rate a purchase falls back to when neither the line nor the position names one (R12.5). */
    static final BigDecimal DEFAULT_PURCHASE_VAT_PERCENT = new BigDecimal("23");

    private static final Set<String> CLEARABLE_PROPERTIES =
            Set.of("referenceNumber", "orderLink", "expectedShipDate", "expectedDeliveryDate", "note");

    private final ExternalOrderDao dao;
    private final ExternalOrderLineDao lineDao;
    private final ExternalOrderDocumentDao documentDao;
    private final ExternalOrderServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ProcurementProjectAccess projectAccess;
    private final ProjectDao projectDao;
    private final MaterialSellerDao sellerDao;
    private final PurchaseRequestDao requestDao;
    private final PurchasePositionDao positionDao;
    private final ProcurementDocumentDao procurementDocumentDao;
    private final VatRateDao vatRateDao;
    private final UserDao userDao;
    private final ApplicationEventPublisher eventPublisher;

    public ExternalOrderService(ExternalOrderDao dao,
                                ExternalOrderLineDao lineDao,
                                ExternalOrderDocumentDao documentDao,
                                ExternalOrderServiceMapper mapper,
                                AuditLogDao auditLogDao,
                                EntityManager entityManager,
                                ProcurementProjectAccess projectAccess,
                                ProjectDao projectDao,
                                MaterialSellerDao sellerDao,
                                PurchaseRequestDao requestDao,
                                PurchasePositionDao positionDao,
                                ProcurementDocumentDao procurementDocumentDao,
                                VatRateDao vatRateDao,
                                UserDao userDao,
                                ApplicationEventPublisher eventPublisher) {
        this.dao = dao;
        this.lineDao = lineDao;
        this.documentDao = documentDao;
        this.mapper = mapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.projectAccess = projectAccess;
        this.projectDao = projectDao;
        this.sellerDao = sellerDao;
        this.requestDao = requestDao;
        this.positionDao = positionDao;
        this.procurementDocumentDao = procurementDocumentDao;
        this.vatRateDao = vatRateDao;
        this.userDao = userDao;
        this.eventPublisher = eventPublisher;
    }

    // --- CRUD plumbing ---

    @Override
    public ExternalOrderDao getDao() {
        return dao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<ExternalOrderEntity, ExternalOrderServiceModel,
            ExternalOrderServiceExtendedModel> getMapper() {
        return mapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<ExternalOrderEntity> getDaoModelClass() {
        return ExternalOrderEntity.class;
    }

    /** R21.2: default sort {@code updatedDate desc}. */
    @Override
    public Sort getDefaultSort() {
        return Sort.by(Sort.Order.desc("updatedDate"), Sort.Order.desc("id"));
    }

    /** R21.3: page totals come from ONE lines query for the whole page (no N+1). */
    @Override
    public org.springframework.data.domain.Page<ExternalOrderServiceModel> find(
            org.springframework.data.domain.Pageable pageable, String rawQuery) {
        org.springframework.data.domain.Pageable processed = org.springframework.data.domain.PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), resolveSort(pageable.getSort()));
        org.springframework.data.domain.Page<ExternalOrderEntity> page =
                dao.findAll(buildFinalSpecification(rawQuery), processed);
        List<ExternalOrderServiceModel> models = mapper.toServiceModels(page.getContent());
        return new org.springframework.data.domain.PageImpl<>(models, processed, page.getTotalElements());
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

    /** The mapper reads the lazy project, seller and lines; keep one session for the whole read. */
    @Override
    @Transactional(readOnly = true)
    public ExternalOrderServiceExtendedModel findById(Long id) {
        return ProjectScopedService.super.findById(id);
    }

    // --- Create (R12.1, R13.4) ---

    @Override
    @Transactional
    public ExternalOrderServiceExtendedModel create(ExternalOrderServiceExtendedModel model) {
        Long projectId = model.getProjectId();
        if (projectId == null || !projectDao.existsById(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, projectId);
        }
        requireProjectAccess(projectId);
        if (model.getSellerId() == null || !sellerDao.existsById(model.getSellerId())) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, model.getSellerId());
        }
        validateRequest(model.getPurchaseRequestId(), projectId);
        if (model.getAssignedBuyerId() == null) {
            model.setAssignedBuyerId(currentUserId());
        } else {
            validateBuyer(model.getAssignedBuyerId());
        }
        BigDecimal cost = normalizeCost(model.getDeliveryCostNet());
        model.setDeliveryCostNet(cost);
        model.setDeliveryVatRateId(resolveDeliveryVatRateId(cost, model.getDeliveryVatRateId()));

        ExternalOrderEntity entity = dao.save(mapper.toCreateDaoModel(model));
        entityManager.flush();
        if (model.getLines() != null && !model.getLines().isEmpty()) {
            applyLines(entity, model.getLines());
        }
        saveAudit(null, entity, "CREATE");
        return mapper.toServiceExtendedModel(entity);
    }

    /** Bulk create would bypass the per-order validation; route each model through {@link #create}. */
    @Override
    @Transactional
    public List<ExternalOrderServiceExtendedModel> create(List<ExternalOrderServiceExtendedModel> models) {
        return models.stream().map(this::create).toList();
    }

    // --- Update header (R12.1, R12.6, R13.1, R13.4) ---

    @Override
    @Transactional
    public ExternalOrderServiceExtendedModel update(Long id, ExternalOrderServiceExtendedModel model) {
        assertProjectAccess(id);
        ExternalOrderEntity entity = load(id);
        requireDraft(entity);
        Long projectId = entity.getProject().getId();
        if (model.getProjectId() != null && !Objects.equals(model.getProjectId(), projectId)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, FIELD_READ_ONLY)
                    .withParams(Map.of("field", "projectId"));
        }
        if (model.getSellerId() != null && !sellerDao.existsById(model.getSellerId())) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, model.getSellerId());
        }
        validateRequest(model.getPurchaseRequestId(), projectId);
        if (model.getAssignedBuyerId() != null) {
            validateBuyer(model.getAssignedBuyerId());
        }
        String beforeSnapshot = serializeEntity(entity);

        BigDecimal cost = model.getDeliveryCostNet() != null
                ? normalizeCost(model.getDeliveryCostNet()) : entity.getDeliveryCostNet();
        Long vatId = model.getDeliveryVatRateId() != null ? model.getDeliveryVatRateId()
                : entity.getDeliveryVatRate() == null ? null : entity.getDeliveryVatRate().getId();
        model.setDeliveryCostNet(model.getDeliveryCostNet() == null ? null : cost);
        model.setDeliveryVatRateId(resolveDeliveryVatRateId(cost, vatId));

        mapper.updateFields(model, entity);
        dao.save(entity);
        entityManager.flush();
        reallocateDelivery(entity, lineDao.findByExternalOrderId(id));
        saveAuditWithSnapshot(beforeSnapshot, entity, "UPDATE");
        return mapper.toServiceExtendedModel(entity);
    }

    /** Single-field writes would skip the draft rule and the delivery allocation; not supported. */
    @Override
    @Transactional
    public <V> void updateSingleField(Long id, V value, BiConsumer<ExternalOrderEntity, V> setter) {
        throw new ForemenApiException(HttpStatus.BAD_REQUEST, FIELD_READ_ONLY)
                .withParams(Map.of("field", "*"));
    }

    /** Only the optional header fields may be cleared, and only on a draft. */
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

    // --- Delete (R12.7) ---

    /** Deletes a DRAFT with its lines and documents; positions stay untouched. */
    @Override
    @Transactional
    public void deleteById(Long id) {
        assertProjectAccess(id);
        requireDraft(load(id));
        lineDao.deleteAll(lineDao.findByExternalOrderId(id));
        documentDao.deleteAll(documentDao.findByExternalOrderId(id));
        entityManager.flush();
        ProjectScopedService.super.deleteById(id);
    }

    @Override
    @Transactional
    public void deleteAll(List<Long> ids) {
        ids.forEach(this::deleteById);
    }

    // --- Lines (R12.1 - R12.5, R13.1) ---

    /** Replaces all lines of a DRAFT (R12.1), reallocating the delivery cost. Positions are not touched. */
    @Transactional
    public ExternalOrderServiceExtendedModel replaceLines(Long id, List<ExternalOrderLineServiceModel> lines) {
        assertProjectAccess(id);
        ExternalOrderEntity entity = load(id);
        requireDraft(entity);
        String beforeSnapshot = serializeEntity(entity);
        applyLines(entity, lines);
        saveAuditWithSnapshot(beforeSnapshot, entity, "UPDATE");
        return mapper.toServiceExtendedModel(entity);
    }

    private void applyLines(ExternalOrderEntity order, List<ExternalOrderLineServiceModel> inputs) {
        List<ExternalOrderLineServiceModel> requested = inputs == null ? List.of() : inputs;
        List<Long> positionIds = new ArrayList<>(requested.size());
        for (ExternalOrderLineServiceModel in : requested) {
            if (in == null || in.getPositionId() == null || in.getQuantity() == null
                    || in.getQuantity().signum() <= 0 || in.getUnitPurchaseNet() == null
                    || in.getUnitPurchaseNet().signum() < 0) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, LINES_INVALID);
            }
            positionIds.add(in.getPositionId());
        }
        if (new HashSet<>(positionIds).size() != positionIds.size()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, LINES_INVALID);
        }

        Long projectId = order.getProject().getId();
        Map<Long, PurchasePositionEntity> positions = new LinkedHashMap<>();
        Map<Long, BigDecimal> otherCoverage = new LinkedHashMap<>();
        if (!positionIds.isEmpty()) {
            positionDao.findAllByIdIn(positionIds).forEach(p -> positions.put(p.getId(), p));
            for (Object[] row : lineDao.sumQuantityByPositionExcluding(positionIds, order.getId())) {
                otherCoverage.put((Long) row[0], (BigDecimal) row[1]);
            }
        }

        List<ExternalOrderLineEntity> fresh = new ArrayList<>(requested.size());
        for (ExternalOrderLineServiceModel in : requested) {
            PurchasePositionEntity p = positions.get(in.getPositionId());
            if (p == null) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, POSITION_NOT_FOUND, in.getPositionId());
            }
            if (!Objects.equals(p.getProject().getId(), projectId)) {
                throw conflict(LINE_PROJECT_MISMATCH, p.getId());
            }
            if (p.getStatus() != PositionStatus.WITH_BUYER
                    || (p.getSourcingPath() != SourcingPath.AGREED && p.getSourcingPath() != SourcingPath.PACKAGE)) {
                throw conflict(POSITION_NOT_PURCHASABLE, p.getId());
            }
            BigDecimal quantity = in.getQuantity().setScale(3, RoundingMode.HALF_UP);
            ExternalOrderCoverageValidator.check(p.getQuantity(), otherCoverage.get(p.getId()), quantity);
            BigDecimal unitNet = in.getUnitPurchaseNet().setScale(2, RoundingMode.HALF_UP);
            if (CeilingCheck.exceeds(unitNet, p.getSalePrice()) && !in.isAboveCeilingAcknowledged()) {
                throw conflict(ABOVE_CEILING, p.getId());
            }
            ExternalOrderLineEntity line = new ExternalOrderLineEntity();
            line.setExternalOrder(order);
            line.setPosition(p);
            line.setQuantity(quantity);
            line.setUnitPurchaseNet(unitNet);
            line.setPurchaseVatRate(resolvePurchaseVatRate(in.getPurchaseVatRateId(), p));
            line.setAboveCeilingAcknowledged(in.isAboveCeilingAcknowledged());
            fresh.add(line);
        }

        reallocateDelivery(order, fresh);

        List<ExternalOrderLineEntity> existing = lineDao.findByExternalOrderId(order.getId());
        if (!existing.isEmpty()) {
            lineDao.deleteAll(existing);
            entityManager.flush();
        }
        lineDao.saveAll(fresh);
        entityManager.flush();
    }

    /** R13.1: shares are proportional to the purchase net of each line and sum exactly to the delivery net. */
    private void reallocateDelivery(ExternalOrderEntity order, List<ExternalOrderLineEntity> lines) {
        if (lines.isEmpty()) {
            return;
        }
        List<BigDecimal> weights = new ArrayList<>(lines.size());
        for (ExternalOrderLineEntity l : lines) {
            weights.add(PositionMoneyMath.purchaseNet(l.getUnitPurchaseNet(), l.getQuantity()));
        }
        List<BigDecimal> shares = DeliveryCostAllocator.allocate(order.getDeliveryCostNet(), weights);
        for (int i = 0; i < lines.size(); i++) {
            lines.get(i).setDeliveryCostShare(shares.get(i));
        }
        if (lines.get(0).getId() != null) {
            lineDao.saveAll(lines);
            entityManager.flush();
        }
    }

    /** R12.5: the line's own rate, else the position's purchase rate, else the dictionary 23% rate. */
    private VatRateEntity resolvePurchaseVatRate(Long requestedId, PurchasePositionEntity position) {
        if (requestedId != null) {
            return vatRateDao.findById(requestedId)
                    .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, requestedId));
        }
        if (position.getPurchaseVatRate() != null) {
            return position.getPurchaseVatRate();
        }
        return defaultVatRate().orElse(null);
    }

    private Optional<VatRateEntity> defaultVatRate() {
        return java.util.stream.StreamSupport.stream(vatRateDao.findAll().spliterator(), false)
                .filter(VatRateEntity::isActive)
                .filter(v -> v.getRate() != null && v.getRate().compareTo(DEFAULT_PURCHASE_VAT_PERCENT) == 0)
                .findFirst();
    }

    // --- Documents (R14) ---

    /** Replaces the linked supplier documents of a DRAFT or SENT_FOR_PAYMENT order (R14.1). */
    @Transactional
    public ExternalOrderServiceExtendedModel replaceDocuments(Long id, List<ExternalOrderDocumentServiceModel> documents) {
        assertProjectAccess(id);
        ExternalOrderEntity entity = load(id);
        if (entity.getStatus() != ExternalOrderStatus.DRAFT
                && entity.getStatus() != ExternalOrderStatus.SENT_FOR_PAYMENT) {
            throw new ForemenApiException(HttpStatus.CONFLICT, DOCUMENTS_LOCKED);
        }
        List<ExternalOrderDocumentServiceModel> requested = documents == null ? List.of() : documents;
        Set<Long> seen = new HashSet<>();
        for (ExternalOrderDocumentServiceModel in : requested) {
            if (in == null || in.getProcurementDocumentId() == null
                    || (in.getAmount() != null && in.getAmount().signum() <= 0)
                    || !seen.add(in.getProcurementDocumentId())) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, DOCUMENTS_INVALID);
            }
        }

        List<ExternalOrderDocumentEntity> fresh = new ArrayList<>(requested.size());
        for (ExternalOrderDocumentServiceModel in : requested) {
            ProcurementDocumentEntity doc = procurementDocumentDao.findById(in.getProcurementDocumentId())
                    .filter(this::callerMayRead)
                    .orElseThrow(() -> new ForemenApiException(
                            HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, in.getProcurementDocumentId()));
            BigDecimal amount = in.getAmount() == null ? null : in.getAmount().setScale(2, RoundingMode.HALF_UP);
            requireWithinDocumentTotal(id, doc, amount);
            ExternalOrderDocumentEntity link = new ExternalOrderDocumentEntity();
            link.setExternalOrder(entity);
            link.setProcurementDocument(doc);
            link.setAmount(amount);
            fresh.add(link);
        }

        String beforeSnapshot = serializeEntity(entity);
        List<ExternalOrderDocumentEntity> existing = documentDao.findByExternalOrderId(id);
        if (!existing.isEmpty()) {
            documentDao.deleteAll(existing);
            entityManager.flush();
        }
        documentDao.saveAll(fresh);
        entityManager.flush();
        saveAuditWithSnapshot(beforeSnapshot, entity, "UPDATE");
        eventPublisher.publishEvent(new ExternalOrderDocumentsChangedEvent(id));
        return mapper.toServiceExtendedModel(entity);
    }

    /** R14.3: Σ of attributed amounts of one document across non-cancelled orders must not exceed its total. */
    private void requireWithinDocumentTotal(Long orderId, ProcurementDocumentEntity doc, BigDecimal amount) {
        if (amount == null || doc.getDocumentTotal() == null) {
            return;
        }
        BigDecimal attributed = BigDecimal.ZERO;
        for (ExternalOrderDocumentEntity other : documentDao.findByProcurementDocumentId(doc.getId())) {
            if (Objects.equals(other.getExternalOrder().getId(), orderId)
                    || other.getExternalOrder().getStatus() == ExternalOrderStatus.CANCELLED
                    || other.getAmount() == null) {
                continue;
            }
            attributed = attributed.add(other.getAmount());
        }
        if (attributed.add(amount).compareTo(doc.getDocumentTotal()) > 0) {
            throw conflict(DOCUMENTS_OVER_ATTRIBUTED, doc.getId());
        }
    }

    /** R14.2: a company-level document (no project) is readable; a project document needs project access. */
    private boolean callerMayRead(ProcurementDocumentEntity doc) {
        if (doc.getProject() == null) {
            return true;
        }
        Caller caller = caller();
        if (caller.admin()) {
            return true;
        }
        Set<Long> allowed = caller.userId() == null ? null : allowedProjectIds(caller.userId());
        return allowed != null && allowed.contains(doc.getProject().getId());
    }

    // --- Validation helpers ---

    private void validateRequest(Long requestId, Long projectId) {
        if (requestId == null) {
            return;
        }
        PurchaseRequestEntity request = requestDao.findById(requestId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, requestId));
        if (!Objects.equals(request.getProject().getId(), projectId)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, REQUEST_PROJECT_MISMATCH);
        }
    }

    /** The buyer must exist, be active and carry the {@code BUYER} role. */
    private void validateBuyer(Long buyerId) {
        boolean valid = userDao.findById(buyerId).filter(UserEntity::isActive).isPresent()
                && userDao.findRoleCodeById(buyerId)
                        .filter(ProcurementProjectAccess.BUYER_ROLE_CODE::equals).isPresent();
        if (!valid) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, BUYER_INVALID);
        }
    }

    /** R13.4: cost >= 0 (scale 2); null is zero. */
    private static BigDecimal normalizeCost(BigDecimal cost) {
        if (cost == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (cost.signum() < 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, DELIVERY_COST_INVALID);
        }
        return cost.setScale(2, RoundingMode.HALF_UP);
    }

    /** R13.4: a VAT rate is required when the cost is positive; absent, it falls back to the 23% rate. */
    private Long resolveDeliveryVatRateId(BigDecimal cost, Long vatRateId) {
        if (vatRateId != null) {
            if (!vatRateDao.existsById(vatRateId)) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, vatRateId);
            }
            return vatRateId;
        }
        if (cost == null || cost.signum() == 0) {
            return null;
        }
        return defaultVatRate().map(VatRateEntity::getId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.BAD_REQUEST, DELIVERY_VAT_REQUIRED));
    }

    private ExternalOrderEntity load(Long id) {
        return dao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, id));
    }

    private static void requireDraft(ExternalOrderEntity entity) {
        if (entity.getStatus() != ExternalOrderStatus.DRAFT) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_DRAFT);
        }
    }

    private static ForemenApiException conflict(String key, Long id) {
        return new ForemenApiException(HttpStatus.CONFLICT, key, id).withParams(Map.of("id", id));
    }

    // --- Caller ---

    private record Caller(boolean admin, Long userId) {
    }

    private static Caller caller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return new Caller(false, null);
        }
        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority()));
        Long userId = null;
        try {
            userId = Long.parseLong(auth.getName().trim());
        } catch (RuntimeException ignored) {
            // non-numeric principal: no user id
        }
        return new Caller(admin, userId);
    }

    private static Long currentUserId() {
        return caller().userId();
    }

    /** Non-ADMIN callers may create only inside their allowed projects; denial is the usual 404. */
    private void requireProjectAccess(Long projectId) {
        Caller caller = caller();
        if (caller.admin()) {
            return;
        }
        Set<Long> allowed = caller.userId() == null ? null : allowedProjectIds(caller.userId());
        if (allowed == null || !allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, projectId);
        }
    }

    // --- Audit snapshot: flat, cycle-free, no purchase prices (R11.7) ---

    @Override
    public String serializeEntity(ExternalOrderEntity e) {
        if (e == null) {
            return null;
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", e.getId());
        snap.put("projectId", e.getProject() == null ? null : e.getProject().getId());
        snap.put("purchaseRequestId", e.getPurchaseRequest() == null ? null : e.getPurchaseRequest().getId());
        snap.put("sellerId", e.getSeller() == null ? null : e.getSeller().getId());
        snap.put("status", e.getStatus());
        snap.put("referenceNumber", e.getReferenceNumber());
        snap.put("expectedShipDate", e.getExpectedShipDate());
        snap.put("expectedDeliveryDate", e.getExpectedDeliveryDate());
        snap.put("assignedBuyerId", e.getAssignedBuyer() == null ? null : e.getAssignedBuyer().getId());
        snap.put("note", e.getNote());
        snap.put("returnReason", e.getReturnReason());
        snap.put("paidSource", e.getPaidSource());
        if (e.getId() != null) {
            snap.put("positionIds", lineDao.findByExternalOrderId(e.getId()).stream()
                    .map(l -> l.getPosition().getId()).toList());
            snap.put("documentIds", documentDao.findByExternalOrderId(e.getId()).stream()
                    .map(d -> d.getProcurementDocument().getId()).toList());
        }
        try {
            return AUDIT_OBJECT_MAPPER.writeValueAsString(snap);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize external order audit snapshot", ex);
        }
    }
}
