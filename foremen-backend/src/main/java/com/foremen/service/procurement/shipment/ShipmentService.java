package com.foremen.service.procurement.shipment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.controller.model.ShipmentDto;
import com.foremen.controller.model.ShipmentFilter;
import com.foremen.controller.model.ShipmentRequest;
import com.foremen.dao.ExternalOrderDao;
import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.GoodsReceiptDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ShipmentDao;
import com.foremen.dao.ShipmentEventDao;
import com.foremen.dao.ShipmentLineDao;
import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.EtaSource;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentEventEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import com.foremen.dao.model.ShipmentSource;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.AdminService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.procurement.PlannedDeliveryDateSynchronizer;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;
import com.foremen.service.procurement.receipt.GoodsReceiptLedgerService;
import com.foremen.service.procurement.schedule.MaterialScheduleRiskService;
import com.foremen.service.procurement.schedule.MaterialScheduleRiskService.ProjectCategory;
import com.foremen.service.procurement.shipment.tracking.CarrierTrackingAdapter;
import com.foremen.service.procurement.shipment.tracking.CarrierTrackingRegistry;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * FOR-11-04 (R3 to R6, R11.3, R11.4, R16, R17): the shipment aggregate write and read side.
 *
 * <ul>
 *   <li>{@link #create}: validates the method fields and lines, locks the positions
 *       ({@code PESSIMISTIC_WRITE}, id order) so two buyers cannot overshoot the cap, moves
 *       {@code SUPPLIER_PAID} positions to {@code IN_DELIVERY} (SYSTEM), recomputes the planned dates and
 *       asks for the first tracking refresh through {@link ShipmentRefreshRequestedEvent}.</li>
 *   <li>{@link #update}, {@link #cancel} (with the {@code IN_DELIVERY -> SUPPLIER_PAID} reversal),
 *       {@link #revertEta}, {@link #list}, {@link #get}.</li>
 *   <li>{@link #onStatusOrEtaChanged}: the callback of the tracking service after it changed a shipment.</li>
 * </ul>
 *
 * <p>Scoping: project id path {@link #PROJECT_ID_PATH}; ADMIN sees all, other callers the projects of
 * {@link ProcurementProjectAccess#allowedProjectIds}; no access reads as 404 (R17.2). The service carries
 * its own scoping and audit instead of the {@code ProjectScopedService} mix-in because the shipment API
 * is a DTO-based aggregate without a service-model mapper pair.
 *
 * <p>Money never enters a shipment: neither the DTO nor the audit snapshot carries a price.
 */
@Service
public class ShipmentService {

    /** JPA path from a shipment to its project id (R1.5, entity-creation checklist step 4). */
    public static final String PROJECT_ID_PATH = "project.id";

    static final String NOT_FOUND = "error.shipment.notFound";
    static final String TERMINAL = "error.shipment.terminal";
    static final String PROJECT_NOT_FOUND = "error.entity.not.found";
    static final String POSITION_NOT_FOUND = "error.purchasePosition.notFound";
    static final String NOT_SHIPPABLE = "error.shipment.position.notShippable";
    static final String EXCEEDS_CAP = "error.shipment.line.exceedsCap";
    static final String EXCEEDS_ORDER_LINE = "error.shipment.line.exceedsOrderLine";
    static final String LINE_DUPLICATE = "error.shipment.line.duplicate";
    static final String LINE_EMPTY = "error.shipment.line.empty";
    static final String PROJECT_MISMATCH = "error.shipment.project.mismatch";
    static final String FIELDS_MISSING = "error.shipment.method.fieldsMissing";
    static final String REVERT_NOT_AVAILABLE = "error.shipment.eta.revertNotAvailable";
    static final String HAS_RECEIPTS = "error.shipment.hasReceipts";
    static final String POSITION_HAS_RECEIPTS = "error.shipment.position.hasReceipts";

    static final int EVENT_CAP = 50;
    static final int QUANTITY_SCALE = 3;

    private static final Set<ShipmentStatus> MANUAL_STATUSES = Set.of(
            ShipmentStatus.CREATED, ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY,
            ShipmentStatus.DELIVERED, ShipmentStatus.EXCEPTION);

    private final ShipmentDao shipmentDao;
    private final ShipmentLineDao lineDao;
    private final ShipmentEventDao eventDao;
    private final ProjectDao projectDao;
    private final ExternalOrderDao externalOrderDao;
    private final ExternalOrderLineDao externalOrderLineDao;
    private final PositionLifecycleService lifecycle;
    private final PlannedDeliveryDateSynchronizer synchronizer;
    private final MaterialScheduleRiskService riskService;
    private final CarrierTrackingRegistry trackingRegistry;
    private final ProcurementProjectAccess projectAccess;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;
    private final GoodsReceiptLedgerService ledgerService;
    private final GoodsReceiptDao receiptDao;
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    private final Clock clock;

    public ShipmentService(ShipmentDao shipmentDao,
                           ShipmentLineDao lineDao,
                           ShipmentEventDao eventDao,
                           ProjectDao projectDao,
                           ExternalOrderDao externalOrderDao,
                           ExternalOrderLineDao externalOrderLineDao,
                           PositionLifecycleService lifecycle,
                           PlannedDeliveryDateSynchronizer synchronizer,
                           MaterialScheduleRiskService riskService,
                           CarrierTrackingRegistry trackingRegistry,
                           ProcurementProjectAccess projectAccess,
                           AuditLogDao auditLogDao,
                           EntityManager entityManager,
                           ApplicationEventPublisher events,
                           GoodsReceiptLedgerService ledgerService,
                           GoodsReceiptDao receiptDao,
                           Clock clock) {
        this.shipmentDao = shipmentDao;
        this.lineDao = lineDao;
        this.eventDao = eventDao;
        this.projectDao = projectDao;
        this.externalOrderDao = externalOrderDao;
        this.externalOrderLineDao = externalOrderLineDao;
        this.lifecycle = lifecycle;
        this.synchronizer = synchronizer;
        this.riskService = riskService;
        this.trackingRegistry = trackingRegistry;
        this.projectAccess = projectAccess;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.events = events;
        this.ledgerService = ledgerService;
        this.receiptDao = receiptDao;
        this.clock = clock;
    }

    // --- Create (R4) ---

    @Transactional
    public ShipmentDto create(ShipmentRequest request) {
        Long projectId = request.projectId();
        ProjectEntity project = projectId == null ? null : projectDao.findById(projectId).orElse(null);
        if (project == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND, projectId);
        }
        requireProjectAccess(projectId);

        ShipmentRules.MethodFields fields = validateMethod(request, request.deliveryMethod());
        validateTimeWindow(request.etaTimeFrom(), request.etaTimeTo());
        ExternalOrderEntity order = resolveOrder(request.externalOrderId(), projectId);
        List<ShipmentRequest.Line> lines = validateLineShape(request.lines());
        Map<Long, PurchasePositionEntity> positions = lockPositions(lines, projectId);
        validateLines(lines, positions, order, -1L);

        OffsetDateTime now = OffsetDateTime.now(clock);
        ShipmentEntity shipment = new ShipmentEntity();
        shipment.setProject(project);
        shipment.setExternalOrder(order);
        shipment.setDeliveryMethod(request.deliveryMethod());
        applyMethodFields(shipment, fields);
        shipment.setStatus(ShipmentStatus.CREATED);
        shipment.setStatusSource(ShipmentSource.MANUAL);
        if (request.etaDate() != null) {
            shipment.setEtaDate(request.etaDate());
            shipment.setEtaTimeFrom(request.etaTimeFrom());
            shipment.setEtaTimeTo(request.etaTimeTo());
            shipment.setEtaSource(EtaSource.MANUAL);
        } else {
            shipment.setEtaSource(EtaSource.TRACKING);
        }
        shipment.setDriverName(blankToNull(request.driverName()));
        shipment.setNote(blankToNull(request.note()));
        shipment.setShippedAt(now);
        boolean trackable = isTrackable(shipment);
        shipment.setNextCheckAt(trackable ? now : null);

        ShipmentEntity saved = shipmentDao.save(shipment);
        saveLines(saved, lines, positions);
        addEvent(saved, ShipmentStatus.CREATED, ShipmentSource.MANUAL, now, null);
        entityManager.flush();

        moveToInDelivery(positions.values());
        propagate(saved, positions.values(), currentUserId());
        saveAudit(null, snapshot(saved, lines), saved, "CREATE");

        events.publishEvent(new ShipmentCreatedEvent(saved.getId(), projectId,
                positions.keySet().stream().sorted().toList(), currentUserId()));
        if (trackable) {
            events.publishEvent(new ShipmentRefreshRequestedEvent(saved.getId()));
        }
        return toDto(saved);
    }

    // --- Update (R5) ---

    @Transactional
    public ShipmentDto update(Long id, ShipmentRequest request) {
        ShipmentEntity shipment = load(id);
        String before = snapshot(shipment, currentLines(shipment));
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (shipment.getStatus().isTerminal()) {
            rejectTerminalChange(shipment, request);
            shipment.setNote(blankToNull(request.note()));
            shipmentDao.save(shipment);
            saveAudit(before, snapshot(shipment, currentLines(shipment)), shipment, "UPDATE");
            return toDto(shipment);
        }

        DeliveryMethod method = request.deliveryMethod() != null ? request.deliveryMethod()
                : shipment.getDeliveryMethod();
        ShipmentRules.MethodFields fields = validateMethod(request, method);
        validateTimeWindow(request.etaTimeFrom(), request.etaTimeTo());

        boolean trackingChanged = method != shipment.getDeliveryMethod()
                || !Objects.equals(fields.carrierCode(), shipment.getCarrierCode())
                || !Objects.equals(fields.trackingNumber(), shipment.getTrackingNumber());
        shipment.setDeliveryMethod(method);
        applyMethodFields(shipment, fields);
        if (trackingChanged) {
            shipment.setConsecutiveFailures(0);
            shipment.setNextCheckAt(isTrackable(shipment) ? now : null);
        }
        shipment.setDriverName(blankToNull(request.driverName()));
        shipment.setNote(blankToNull(request.note()));

        if (request.etaDate() != null && (!request.etaDate().equals(shipment.getEtaDate())
                || !Objects.equals(request.etaTimeFrom(), shipment.getEtaTimeFrom())
                || !Objects.equals(request.etaTimeTo(), shipment.getEtaTimeTo()))) {
            shipment.setEtaDate(request.etaDate());
            shipment.setEtaTimeFrom(request.etaTimeFrom());
            shipment.setEtaTimeTo(request.etaTimeTo());
            shipment.setEtaSource(EtaSource.MANUAL);
        }
        if (request.status() != null && request.status() != shipment.getStatus()) {
            applyManualStatus(shipment, request.status(), now);
        }

        Set<PurchasePositionEntity> affected = new LinkedHashSet<>();
        List<ShipmentRequest.Line> lines = null;
        if (request.lines() != null && !sameLines(request.lines(), shipment.getId())) {
            if (shipment.getStatus() != ShipmentStatus.CREATED) {
                throw new ForemenApiException(HttpStatus.CONFLICT, TERMINAL);
            }
            lines = validateLineShape(request.lines());
            affected.addAll(replaceLines(shipment, lines));
        }
        if (affected.isEmpty()) {
            affected.addAll(positionsOf(shipment));
        }

        shipmentDao.save(shipment);
        entityManager.flush();
        propagate(shipment, affected, currentUserId());
        saveAudit(before, snapshot(shipment, currentLines(shipment)), shipment, "UPDATE");
        if (trackingChanged && isTrackable(shipment)) {
            events.publishEvent(new ShipmentRefreshRequestedEvent(shipment.getId()));
        }
        return toDto(shipment);
    }

    // --- Cancel (R6) ---

    @Transactional
    public ShipmentDto cancel(Long id) {
        ShipmentEntity shipment = load(id);
        if (shipment.getStatus().isTerminal()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, TERMINAL);
        }
        if (receiptDao.existsActiveByShipmentId(shipment.getId())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, HAS_RECEIPTS);
        }
        String before = snapshot(shipment, currentLines(shipment));
        shipment.setStatus(ShipmentStatus.CANCELLED);
        shipment.setStatusSource(ShipmentSource.MANUAL);
        shipment.setNextCheckAt(null);
        shipmentDao.save(shipment);
        addEvent(shipment, ShipmentStatus.CANCELLED, ShipmentSource.MANUAL, OffsetDateTime.now(clock), null);
        entityManager.flush();

        List<PurchasePositionEntity> positions = positionsOf(shipment);
        reverseToSupplierPaid(positions);
        propagate(shipment, positions, currentUserId());
        saveAudit(before, snapshot(shipment, currentLines(shipment)), shipment, "UPDATE");
        return toDto(shipment);
    }

    // --- ETA revert (R5.2) ---

    @Transactional
    public ShipmentDto revertEta(Long id) {
        ShipmentEntity shipment = load(id);
        if (shipment.getStatus().isTerminal()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, TERMINAL);
        }
        if (shipment.getEtaSource() != EtaSource.MANUAL || !isTrackable(shipment)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, REVERT_NOT_AVAILABLE);
        }
        String before = snapshot(shipment, currentLines(shipment));
        shipment.setEtaSource(EtaSource.TRACKING);
        shipment.setEtaDate(null);
        shipment.setEtaTimeFrom(null);
        shipment.setEtaTimeTo(null);
        shipment.setNextCheckAt(OffsetDateTime.now(clock));
        shipmentDao.save(shipment);
        entityManager.flush();
        propagate(shipment, positionsOf(shipment), currentUserId());
        saveAudit(before, snapshot(shipment, currentLines(shipment)), shipment, "UPDATE");
        events.publishEvent(new ShipmentRefreshRequestedEvent(shipment.getId()));
        return toDto(shipment);
    }

    // --- Tracking callback (design section 4 / 6) ---

    /**
     * Called by the tracking service after it changed status or ETA of {@code shipment}: recomputes the
     * planned dates of the shipment's positions and publishes the delay and downtime events (actor
     * {@code null}). Joins the caller's transaction.
     */
    @Transactional
    public void onStatusOrEtaChanged(ShipmentEntity shipment, Long actorUserId) {
        propagate(shipment, positionsOf(shipment), actorUserId);
    }

    // --- FOR-11-05: receipt-driven writes (internal, no ABAC; the caller has checked the scope) ---

    /**
     * FOR-11-05 R8.1, R8.2: opens a system re-delivery shipment for {@code quantity} units of a position
     * after a receipt released units. No {@link ShipmentCreatedEvent} is published, so no "shipped" notice
     * goes out. Returns the id of the new shipment.
     */
    @Transactional
    public Long createRedelivery(Long positionId, BigDecimal quantity, Long originReceiptId) {
        PurchasePositionEntity position = entityManager.find(PurchasePositionEntity.class, positionId,
                LockModeType.PESSIMISTIC_WRITE);
        if (position == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, POSITION_NOT_FOUND);
        }
        List<ShipmentRequest.Line> lines = validateLineShape(
                List.of(new ShipmentRequest.Line(positionId, quantity)));
        Map<Long, PurchasePositionEntity> positions = new LinkedHashMap<>();
        positions.put(positionId, position);

        Map<Long, ExternalOrderEntity> orders = new HashMap<>();
        boolean anyWithoutOrder = false;
        for (ShipmentLineEntity l : receiptDao.findFifoLinesByPositionId(positionId)) {
            ExternalOrderEntity o = l.getShipment().getExternalOrder();
            if (o == null) {
                anyWithoutOrder = true;
            } else {
                orders.put(o.getId(), o);
            }
        }
        ExternalOrderEntity order = !anyWithoutOrder && orders.size() == 1
                ? orders.values().iterator().next() : null;

        validateLines(lines, positions, order, -1L);

        OffsetDateTime now = OffsetDateTime.now(clock);
        ShipmentEntity shipment = new ShipmentEntity();
        shipment.setProject(position.getProject());
        shipment.setExternalOrder(order);
        shipment.setDeliveryMethod(DeliveryMethod.SHOP_DELIVERY);
        shipment.setStatus(ShipmentStatus.CREATED);
        shipment.setStatusSource(ShipmentSource.MANUAL);
        shipment.setEtaSource(EtaSource.TRACKING);
        shipment.setNote("Re-delivery after receipt " + originReceiptId);
        shipment.setOriginReceiptId(originReceiptId);
        shipment.setShippedAt(now);
        shipment.setNextCheckAt(null);

        ShipmentEntity saved = shipmentDao.save(shipment);
        saveLines(saved, lines, positions);
        addEvent(saved, ShipmentStatus.CREATED, ShipmentSource.MANUAL, now, null);
        entityManager.flush();

        moveToInDelivery(positions.values());
        propagate(saved, positions.values(), currentUserId());
        saveAudit(null, snapshot(saved, lines), saved, "CREATE");
        return saved.getId();
    }

    /**
     * FOR-11-05 R8.6: closes a non-terminal shipment whose lines are all resolved by receipt events. A
     * terminal shipment is left as it is.
     */
    @Transactional
    public void closeByReceipt(Long shipmentId) {
        ShipmentEntity shipment = shipmentId == null ? null : shipmentDao.findById(shipmentId).orElse(null);
        if (shipment == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND);
        }
        if (shipment.getStatus().isTerminal()) {
            return;
        }
        String before = snapshot(shipment, currentLines(shipment));
        OffsetDateTime now = OffsetDateTime.now(clock);
        shipment.setStatus(ShipmentStatus.DELIVERED);
        shipment.setStatusSource(ShipmentSource.MANUAL);
        shipment.setDeliveredAt(now);
        shipment.setNextCheckAt(null);
        shipmentDao.save(shipment);
        addEvent(shipment, ShipmentStatus.DELIVERED, ShipmentSource.SYSTEM, now, "Closed by goods receipt");
        entityManager.flush();
        propagate(shipment, positionsOf(shipment), currentUserId());
        saveAudit(before, snapshot(shipment, currentLines(shipment)), shipment, "UPDATE");
    }

    // --- Reads (R16) ---

    @Transactional(readOnly = true)
    public ShipmentDto get(Long id) {
        return toDto(load(id));
    }

    /** List sorted by ETA ascending (nulls last), then id; scoped to the caller's projects (R16.6). */
    @Transactional(readOnly = true)
    public Page<ShipmentDto> list(ShipmentFilter filter, Pageable pageable) {
        Set<Long> allowed = allowedProjectIdsOrNull();
        if (allowed != null && allowed.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        Pageable processed = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.asc("etaDate").nullsLast(), Sort.Order.asc("id")));
        Page<ShipmentEntity> page = shipmentDao.findAll(specification(filter, allowed), processed);
        return new PageImpl<>(page.getContent().stream().map(this::toDto).toList(), processed,
                page.getTotalElements());
    }

    private Specification<ShipmentEntity> specification(ShipmentFilter f, Set<Long> allowed) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (allowed != null) {
                ps.add(root.get("project").get("id").in(allowed));
            }
            if (f != null) {
                if (f.projectId() != null) {
                    ps.add(cb.equal(root.get("project").get("id"), f.projectId()));
                }
                if (f.status() != null) {
                    ps.add(cb.equal(root.get("status"), f.status()));
                }
                if (f.carrierCode() != null && !f.carrierCode().isBlank()) {
                    ps.add(cb.equal(root.get("carrierCode"), f.carrierCode().trim().toUpperCase(Locale.ROOT)));
                }
                if (f.externalOrderId() != null) {
                    ps.add(cb.equal(root.get("externalOrder").get("id"), f.externalOrderId()));
                }
                if (f.positionId() != null) {
                    Subquery<Long> sub = query.subquery(Long.class);
                    Root<ShipmentLineEntity> l = sub.from(ShipmentLineEntity.class);
                    sub.select(l.get("id")).where(cb.equal(l.get("shipment"), root),
                            cb.equal(l.get("position").get("id"), f.positionId()));
                    ps.add(cb.exists(sub));
                }
                if (f.etaFrom() != null) {
                    ps.add(cb.greaterThanOrEqualTo(root.get("etaDate"), f.etaFrom()));
                }
                if (f.etaTo() != null) {
                    ps.add(cb.lessThanOrEqualTo(root.get("etaDate"), f.etaTo()));
                }
                if (f.degraded() != null) {
                    Predicate degraded = cb.greaterThanOrEqualTo(root.get("consecutiveFailures"),
                            TrackingBackoff.DEGRADED_THRESHOLD);
                    ps.add(f.degraded() ? degraded : cb.not(degraded));
                }
                if (f.delayed() != null) {
                    LocalDate today = LocalDate.now(clock.withZone(WARSAW));
                    Predicate delayed = cb.and(
                            cb.not(root.get("status").in(ShipmentStatus.DELIVERED, ShipmentStatus.CANCELLED)),
                            cb.isNotNull(root.get("etaDate")),
                            cb.lessThan(root.get("etaDate"), today));
                    ps.add(f.delayed() ? delayed : cb.not(delayed));
                }
                if (f.text() != null && !f.text().isBlank()) {
                    String like = "%" + f.text().trim().toLowerCase(Locale.ROOT)
                            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                    ps.add(cb.or(
                            cb.like(cb.lower(root.get("trackingNumber")), like, '\\'),
                            cb.like(cb.lower(root.get("driverName")), like, '\\')));
                }
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    // --- Validation helpers ---

    private ShipmentRules.MethodFields validateMethod(ShipmentRequest r, DeliveryMethod method) {
        return ShipmentRules.validateMethod(method, r.carrierCode(), r.trackingNumber(), r.trackingUrl(),
                r.shopOrderLink(), r.driverPhone(), trackingRegistry.allCodes());
    }

    private static void validateTimeWindow(LocalTime from, LocalTime to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, FIELDS_MISSING);
        }
    }

    private ExternalOrderEntity resolveOrder(Long orderId, Long projectId) {
        if (orderId == null) {
            return null;
        }
        ExternalOrderEntity order = externalOrderDao.findById(orderId).orElseThrow(
                () -> new ForemenApiException(HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND, orderId));
        if (!Objects.equals(order.getProject().getId(), projectId)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, PROJECT_MISMATCH);
        }
        return order;
    }

    /** R4.4: not empty, no duplicate positions, quantity {@code > 0} with scale at most 3. */
    private static List<ShipmentRequest.Line> validateLineShape(List<ShipmentRequest.Line> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, LINE_EMPTY);
        }
        Set<Long> seen = new HashSet<>();
        List<ShipmentRequest.Line> normalised = new ArrayList<>();
        for (ShipmentRequest.Line line : lines) {
            if (line == null || line.positionId() == null || line.quantity() == null
                    || line.quantity().signum() <= 0
                    || line.quantity().stripTrailingZeros().scale() > QUANTITY_SCALE) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, LINE_EMPTY);
            }
            if (!seen.add(line.positionId())) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, LINE_DUPLICATE);
            }
            normalised.add(new ShipmentRequest.Line(line.positionId(),
                    line.quantity().setScale(QUANTITY_SCALE, java.math.RoundingMode.UNNECESSARY)));
        }
        return normalised;
    }

    /** Locks the positions in id order (no deadlock between two buyers) and checks the project (R2.5). */
    private Map<Long, PurchasePositionEntity> lockPositions(List<ShipmentRequest.Line> lines, Long projectId) {
        Map<Long, PurchasePositionEntity> positions = new LinkedHashMap<>();
        for (Long positionId : new TreeSet<>(lines.stream().map(ShipmentRequest.Line::positionId).toList())) {
            PurchasePositionEntity p = entityManager.find(PurchasePositionEntity.class, positionId,
                    LockModeType.PESSIMISTIC_WRITE);
            if (p == null) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, POSITION_NOT_FOUND);
            }
            if (!Objects.equals(p.getProject().getId(), projectId)) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, PROJECT_MISMATCH);
            }
            positions.put(positionId, p);
        }
        return positions;
    }

    /** R4.2 to R4.4: stage, cap and order-line quantity. */
    private void validateLines(List<ShipmentRequest.Line> lines, Map<Long, PurchasePositionEntity> positions,
                               ExternalOrderEntity order, Long excludeShipmentId) {
        for (ShipmentRequest.Line line : lines) {
            PurchasePositionEntity p = positions.get(line.positionId());
            if (!ShipmentRules.canShip(p.getSourcingPath(), p.getStatus())) {
                throw new ForemenApiException(HttpStatus.CONFLICT, NOT_SHIPPABLE, p.getId())
                        .withParams(Map.of("positionId", p.getId()));
            }
        }
        for (ShipmentRequest.Line line : lines) {
            Long positionId = line.positionId();
            if (receiptDao.existsActiveByPositionId(positionId)) {
                BigDecimal onShipments = lineDao.sumActiveQuantityByPositionId(positionId);
                if (onShipments == null || onShipments.signum() == 0) {
                    throw new ForemenApiException(HttpStatus.CONFLICT, POSITION_HAS_RECEIPTS, positionId)
                            .withParams(Map.of("positionId", positionId));
                }
            }
        }
        Map<Long, BigDecimal> shipped = ledgerService.committed(positions.keySet(), excludeShipmentId);
        for (ShipmentRequest.Line line : lines) {
            PurchasePositionEntity p = positions.get(line.positionId());
            BigDecimal cap = ShipmentRules.capFor(p.getSourcingPath(), p.getOrderedQuantity(), p.getQuantity());
            BigDecimal total = shipped.getOrDefault(p.getId(), BigDecimal.ZERO).add(line.quantity());
            if (cap == null || total.compareTo(cap) > 0) {
                throw new ForemenApiException(HttpStatus.CONFLICT, EXCEEDS_CAP, p.getId())
                        .withParams(Map.of("positionId", p.getId()));
            }
        }
        if (order != null) {
            Map<Long, BigDecimal> orderLines = new HashMap<>();
            for (ExternalOrderLineEntity ol : externalOrderLineDao.findByExternalOrderId(order.getId())) {
                orderLines.merge(ol.getPosition().getId(), ol.getQuantity(), BigDecimal::add);
            }
            Map<Long, BigDecimal> onOrder = sums(lineDao.sumActiveQuantityByOrderExcluding(
                    order.getId(), positions.keySet(), excludeShipmentId));
            Map<Long, BigDecimal> released = ledgerService.releasedByOrder(order.getId(), positions.keySet());
            for (ShipmentRequest.Line line : lines) {
                BigDecimal orderQty = orderLines.get(line.positionId());
                BigDecimal open = onOrder.getOrDefault(line.positionId(), BigDecimal.ZERO)
                        .subtract(released.getOrDefault(line.positionId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
                BigDecimal total = open.add(line.quantity());
                if (orderQty == null || total.compareTo(orderQty) > 0) {
                    throw new ForemenApiException(HttpStatus.CONFLICT, EXCEEDS_ORDER_LINE, line.positionId())
                            .withParams(Map.of("positionId", line.positionId()));
                }
            }
        }
    }

    private static Map<Long, BigDecimal> sums(List<Object[]> rows) {
        Map<Long, BigDecimal> map = new HashMap<>();
        if (rows != null) {
            for (Object[] row : rows) {
                map.put(((Number) row[0]).longValue(), (BigDecimal) row[1]);
            }
        }
        return map;
    }

    // --- Mutation helpers ---

    private void applyMethodFields(ShipmentEntity s, ShipmentRules.MethodFields f) {
        s.setCarrierCode(f.carrierCode());
        s.setTrackingNumber(f.trackingNumber());
        s.setTrackingUrl(f.trackingUrl());
        s.setShopOrderLink(f.shopOrderLink());
        s.setDriverPhone(f.driverPhone());
    }

    private void applyManualStatus(ShipmentEntity s, ShipmentStatus status, OffsetDateTime now) {
        if (!MANUAL_STATUSES.contains(status)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, FIELDS_MISSING);
        }
        s.setStatus(status);
        s.setStatusSource(ShipmentSource.MANUAL);
        addEvent(s, status, ShipmentSource.MANUAL, now, null);
        if (status == ShipmentStatus.DELIVERED) {
            s.setDeliveredAt(now);
            s.setNextCheckAt(null);
        }
    }

    private void addEvent(ShipmentEntity s, ShipmentStatus status, ShipmentSource source, OffsetDateTime at,
                          String note) {
        ShipmentEventEntity e = new ShipmentEventEntity();
        e.setShipment(s);
        e.setStatus(status);
        e.setSource(source);
        e.setOccurredAt(at);
        e.setNote(note);
        eventDao.save(e);
    }

    private void saveLines(ShipmentEntity shipment, List<ShipmentRequest.Line> lines,
                           Map<Long, PurchasePositionEntity> positions) {
        for (ShipmentRequest.Line line : lines) {
            ShipmentLineEntity l = new ShipmentLineEntity();
            l.setShipment(shipment);
            l.setPosition(positions.get(line.positionId()));
            l.setQuantity(line.quantity());
            lineDao.save(l);
        }
    }

    /** Replaces the lines of a {@code CREATED} shipment; returns every position touched (old and new). */
    private Set<PurchasePositionEntity> replaceLines(ShipmentEntity shipment, List<ShipmentRequest.Line> lines) {
        Long projectId = shipment.getProject().getId();
        List<PurchasePositionEntity> old = positionsOf(shipment);
        Map<Long, PurchasePositionEntity> positions = lockPositions(lines, projectId);
        validateLines(lines, positions, shipment.getExternalOrder(), shipment.getId());
        lineDao.deleteByShipmentId(shipment.getId());
        entityManager.flush();
        saveLines(shipment, lines, positions);
        entityManager.flush();
        moveToInDelivery(positions.values());
        List<PurchasePositionEntity> removed = old.stream()
                .filter(p -> !positions.containsKey(p.getId())).toList();
        reverseToSupplierPaid(removed);
        Set<PurchasePositionEntity> touched = new LinkedHashSet<>(old);
        touched.addAll(positions.values());
        return touched;
    }

    /**
     * R4.6 and FOR-11-05 R8.3: {@code SUPPLIER_PAID} and {@code PARTIALLY_RECEIVED} positions go to
     * {@code IN_DELIVERY}; others stay as they are.
     */
    private void moveToInDelivery(Collection<PurchasePositionEntity> positions) {
        for (PurchasePositionEntity p : positions) {
            if (p.getStatus() == PositionStatus.SUPPLIER_PAID
                    || p.getStatus() == PositionStatus.PARTIALLY_RECEIVED) {
                lifecycle.transition(p.getId(), PositionStatus.IN_DELIVERY, Actor.SYSTEM);
            }
        }
    }

    /**
     * R6.3: a position without any other Active_Shipment (and without a delivered one, which would mean it
     * awaits receipt; see the "Delivered shipment guard" note in requirements R6.3), still {@code IN_DELIVERY} with nothing accepted, goes back to {@code SUPPLIER_PAID}.
     * {@code CLIENT_OWN} positions are never moved back.
     */
    private void reverseToSupplierPaid(Collection<PurchasePositionEntity> positions) {
        if (positions.isEmpty()) {
            return;
        }
        Set<Long> withOpenShipment = new HashSet<>();
        for (ShipmentLineEntity l : lineDao.findActiveByPositionIds(
                positions.stream().map(PurchasePositionEntity::getId).toList())) {
            withOpenShipment.add(l.getPosition().getId());
        }
        for (PurchasePositionEntity p : positions) {
            boolean noAcceptance = p.getAcceptedQuantity() == null || p.getAcceptedQuantity().signum() == 0;
            if (p.getStatus() == PositionStatus.IN_DELIVERY
                    && p.getSourcingPath() != SourcingPath.CLIENT_OWN
                    && noAcceptance
                    && !withOpenShipment.contains(p.getId())
                    && !lineDao.existsDeliveredByPositionId(p.getId())
                    && !receiptDao.existsActiveByPositionId(p.getId())) {
                lifecycle.transition(p.getId(), PositionStatus.SUPPLIER_PAID, Actor.SYSTEM);
            }
        }
    }

    // --- ETA propagation, delay and downtime events (R11) ---

    /**
     * Recomputes the planned date of each position and publishes at most one
     * {@link ShipmentEtaDelayedEvent} (the latest delayed position) and one {@link ShipmentDowntimeRiskEvent}
     * (once per distinct ETA date) for the shipment.
     */
    private void propagate(ShipmentEntity shipment, Collection<PurchasePositionEntity> positions, Long actor) {
        Long projectId = shipment.getProject().getId();
        LocalDate delayedOld = null;
        LocalDate delayedNew = null;
        for (PurchasePositionEntity p : positions) {
            LocalDate before = p.getPlannedDeliveryDate();
            synchronizer.onShipmentChanged(p.getId());
            LocalDate after = p.getPlannedDeliveryDate();
            if (ShipmentRules.isDelayed(before, after) && (delayedNew == null || after.isAfter(delayedNew))) {
                delayedOld = before;
                delayedNew = after;
            }
        }
        if (!shipment.getStatus().isTerminal()) {
            if (delayedNew != null) {
                events.publishEvent(new ShipmentEtaDelayedEvent(
                        shipment.getId(), projectId, delayedOld, delayedNew, actor));
            }
            publishDowntimeRisk(shipment, positions, actor);
        }
    }

    private void publishDowntimeRisk(ShipmentEntity shipment, Collection<PurchasePositionEntity> positions,
                                     Long actor) {
        LocalDate eta = shipment.getEtaDate();
        if (eta == null || eta.equals(shipment.getDowntimeNotifiedFor())) {
            return;
        }
        Long projectId = shipment.getProject().getId();
        Set<ProjectCategory> pairs = new HashSet<>();
        for (PurchasePositionEntity p : positions) {
            if (p.getWorkCategory() != null) {
                pairs.add(new ProjectCategory(projectId, p.getWorkCategory().getId()));
            }
        }
        if (pairs.isEmpty()) {
            return;
        }
        Map<ProjectCategory, LocalDate> starts = riskService.worksStarts(pairs);
        for (PurchasePositionEntity p : positions) {
            if (p.getWorkCategory() == null) {
                continue;
            }
            LocalDate start = starts.get(new ProjectCategory(projectId, p.getWorkCategory().getId()));
            if (MaterialScheduleRiskService.downtimeRisk(p.getStatus(), eta, start)) {
                shipment.setDowntimeNotifiedFor(eta);
                shipmentDao.save(shipment);
                events.publishEvent(new ShipmentDowntimeRiskEvent(shipment.getId(), projectId, eta, actor));
                return;
            }
        }
    }

    // --- Loading and scoping ---

    private ShipmentEntity load(Long id) {
        ShipmentEntity s = id == null ? null : shipmentDao.findById(id).orElse(null);
        if (s == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND);
        }
        Set<Long> allowed = allowedProjectIdsOrNull();
        if (allowed != null && !allowed.contains(s.getProject().getId())) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND);
        }
        return s;
    }

    private void requireProjectAccess(Long projectId) {
        Set<Long> allowed = allowedProjectIdsOrNull();
        if (allowed != null && !allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, PROJECT_NOT_FOUND, projectId);
        }
    }

    /** {@code null} = unrestricted (ADMIN, or no authentication context such as the tracking scheduler). */
    private Set<Long> allowedProjectIdsOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        if (hasAuthority(auth, "ADMIN")) {
            return null;
        }
        Long userId = parseUserId(auth);
        return userId == null ? Set.of() : projectAccess.allowedProjectIds(userId);
    }

    private List<PurchasePositionEntity> positionsOf(ShipmentEntity shipment) {
        return lineDao.findByShipmentId(shipment.getId()).stream().map(ShipmentLineEntity::getPosition).toList();
    }

    private List<ShipmentRequest.Line> currentLines(ShipmentEntity shipment) {
        return lineDao.findByShipmentId(shipment.getId()).stream()
                .map(l -> new ShipmentRequest.Line(l.getPosition().getId(), l.getQuantity())).toList();
    }

    private boolean sameLines(List<ShipmentRequest.Line> requested, Long shipmentId) {
        Map<Long, BigDecimal> current = new HashMap<>();
        for (ShipmentLineEntity l : lineDao.findByShipmentId(shipmentId)) {
            current.put(l.getPosition().getId(), l.getQuantity());
        }
        if (requested.size() != current.size()) {
            return false;
        }
        for (ShipmentRequest.Line line : requested) {
            BigDecimal q = current.get(line.positionId());
            if (q == null || line.quantity() == null || q.compareTo(line.quantity()) != 0) {
                return false;
            }
        }
        return true;
    }

    /** R5.5: a terminal shipment accepts only a note change. */
    private void rejectTerminalChange(ShipmentEntity s, ShipmentRequest r) {
        boolean statusChange = r.status() != null && r.status() != s.getStatus();
        boolean etaChange = r.etaDate() != null && !r.etaDate().equals(s.getEtaDate());
        boolean lineChange = r.lines() != null && !sameLines(r.lines(), s.getId());
        if (statusChange || etaChange || lineChange) {
            throw new ForemenApiException(HttpStatus.CONFLICT, TERMINAL);
        }
    }

    private boolean isTrackable(ShipmentEntity s) {
        return s.getDeliveryMethod() == DeliveryMethod.CARRIER && s.getCarrierCode() != null
                && !ShipmentRules.OTHER_CARRIER.equals(s.getCarrierCode())
                && trackingRegistry.find(s.getCarrierCode()).isPresent();
    }

    // --- Mapping (R16.2 to R16.4) ---

    ShipmentDto toDto(ShipmentEntity s) {
        boolean pl = !"ru".equalsIgnoreCase(LocaleContextHolder.getLocale().getLanguage());
        List<ShipmentDto.Line> lines = lineDao.findByShipmentId(s.getId()).stream()
                .map(l -> toLine(l, pl)).toList();
        List<ShipmentEventEntity> history = new ArrayList<>(eventDao.findByShipmentId(s.getId()));
        history.sort(Comparator.comparing(ShipmentEventEntity::getOccurredAt,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(ShipmentEventEntity::getId,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        List<ShipmentDto.Event> eventDtos = history.stream().limit(EVENT_CAP)
                .map(e -> new ShipmentDto.Event(e.getStatus(), e.getOccurredAt(), e.getSource(), e.getNote()))
                .toList();
        ExternalOrderEntity order = s.getExternalOrder();
        boolean open = !s.getStatus().isTerminal();
        boolean refreshAvailable = open && isTrackable(s);
        return new ShipmentDto(
                s.getId(), s.getProject().getId(), s.getProject().getName(),
                order == null ? null : order.getId(), order == null ? null : order.getReferenceNumber(),
                s.getDeliveryMethod(), s.getCarrierCode(), s.getTrackingNumber(), s.getTrackingUrl(),
                s.getShopOrderLink(), s.getStatus(), s.getStatusSource(), s.getEtaDate(), s.getEtaTimeFrom(),
                s.getEtaTimeTo(), s.getEtaSource(), s.getDriverName(), s.getDriverPhone(),
                isForemanOnly() ? null : s.getNote(),
                s.getShippedAt(), s.getDeliveredAt(), s.getLastEventAt(), s.getLastCheckedAt(),
                TrackingBackoff.isDegraded(s.getConsecutiveFailures()), isSuspect(s), trackingPageUrl(s),
                refreshAvailable, null, lines, eventDtos);
    }

    private static ShipmentDto.Line toLine(ShipmentLineEntity l, boolean pl) {
        PurchasePositionEntity p = l.getPosition();
        FinishingMaterialEntity m = p.getActualMaterial() != null ? p.getActualMaterial() : p.getPlannedMaterial();
        String producer = m.getProducer() == null ? null
                : pl ? m.getProducer().getNamePL() : m.getProducer().getNameRU();
        String label = producer == null || producer.isBlank() ? m.getModel() : m.getModel() + " (" + producer + ")";
        String unit = pl ? p.getUnit().getNamePL() : p.getUnit().getNameRU();
        return new ShipmentDto.Line(p.getId(), label, unit, l.getQuantity(), p.getStatus());
    }

    private boolean isSuspect(ShipmentEntity s) {
        if (s.getDeliveryMethod() != DeliveryMethod.CARRIER || s.getTrackingNumber() == null
                || s.getCarrierCode() == null || ShipmentRules.OTHER_CARRIER.equals(s.getCarrierCode())) {
            return false;
        }
        return trackingRegistry.findAny(s.getCarrierCode())
                .map(a -> !a.matches(s.getTrackingNumber())).orElse(false);
    }

    private String trackingPageUrl(ShipmentEntity s) {
        if (s.getDeliveryMethod() != DeliveryMethod.CARRIER || s.getCarrierCode() == null) {
            return null;
        }
        if (ShipmentRules.OTHER_CARRIER.equals(s.getCarrierCode())) {
            return s.getTrackingUrl();
        }
        if (s.getTrackingNumber() == null) {
            return null;
        }
        return trackingRegistry.findAny(s.getCarrierCode())
                .map((CarrierTrackingAdapter a) -> a.trackingPageUrl(s.getTrackingNumber())).orElse(null);
    }

    /** R16.4: a caller whose only procurement-side role is FOREMAN does not see the buyer's note. */
    private static boolean isForemanOnly() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return hasAuthority(auth, "FOREMAN") && !hasAuthority(auth, "ADMIN") && !hasAuthority(auth, "MANAGER")
                && !hasAuthority(auth, "BUYER");
    }

    private static boolean hasAuthority(Authentication auth, String code) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (code.equals(a) || ("ROLE_" + code).equals(a)) {
                return true;
            }
        }
        return false;
    }

    private static Long parseUserId(Authentication auth) {
        try {
            return Long.parseLong(auth.getName().trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() ? null : parseUserId(auth);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // --- Audit (R5.7): flat snapshot, ids and states only, no money, no phone, no free text ---

    String snapshot(ShipmentEntity s, List<ShipmentRequest.Line> lines) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", s.getId());
        snap.put("projectId", s.getProject() == null ? null : s.getProject().getId());
        snap.put("externalOrderId", s.getExternalOrder() == null ? null : s.getExternalOrder().getId());
        snap.put("deliveryMethod", s.getDeliveryMethod());
        snap.put("carrierCode", s.getCarrierCode());
        snap.put("trackingNumber", s.getTrackingNumber());
        snap.put("status", s.getStatus());
        snap.put("statusSource", s.getStatusSource());
        snap.put("etaDate", s.getEtaDate());
        snap.put("etaTimeFrom", s.getEtaTimeFrom());
        snap.put("etaTimeTo", s.getEtaTimeTo());
        snap.put("etaSource", s.getEtaSource());
        snap.put("lines", lines == null ? List.of() : lines.stream()
                .map(l -> Map.of("positionId", l.positionId(), "quantity", l.quantity())).toList());
        try {
            return AdminService.AUDIT_OBJECT_MAPPER.writeValueAsString(snap);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize shipment audit snapshot", ex);
        }
    }

    private void saveAudit(String before, String after, ShipmentEntity s, String operation) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuditLogEntity log = new AuditLogEntity();
        log.setEntityClass(ShipmentEntity.class.getSimpleName());
        log.setEntityId(s.getId());
        log.setOperation(operation);
        log.setPerformedBy(auth != null && auth.isAuthenticated() ? auth.getName() : "SYSTEM");
        log.setPerformedAt(LocalDateTime.now(clock));
        log.setSnapshotBefore(before);
        log.setSnapshotAfter(after);
        auditLogDao.save(log);
    }
}
