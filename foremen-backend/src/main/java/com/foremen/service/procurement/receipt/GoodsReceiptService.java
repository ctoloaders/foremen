package com.foremen.service.procurement.receipt;

import com.foremen.controller.model.GoodsReceiptBatchResult;
import com.foremen.controller.model.GoodsReceiptDto;
import com.foremen.controller.model.GoodsReceiptEventRequest;
import com.foremen.controller.model.GoodsReceiptFilter;
import com.foremen.controller.model.GoodsReceiptMediaDto;
import com.foremen.controller.model.GoodsReceiptRequest;
import com.foremen.controller.model.GoodsReceiptVoidRequest;
import com.foremen.controller.model.GoodsReceiptVoidResult;
import com.foremen.controller.model.LedgerDto;
import com.foremen.controller.model.PositionLedgerDto;
import com.foremen.controller.model.ToReceiveDto;
import com.foremen.dao.GoodsReceiptDao;
import com.foremen.dao.GoodsReceiptDelegationDao;
import com.foremen.dao.GoodsReceiptMediaDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.ShipmentDao;
import com.foremen.dao.ShipmentLineDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.GoodsReceiptDelegationEntity;
import com.foremen.dao.model.GoodsReceiptEntity;
import com.foremen.dao.model.GoodsReceiptMediaEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ReceiptKind;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.AdminService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;
import com.foremen.service.procurement.receipt.GoodsReceiptRules.Ledger;
import com.foremen.service.procurement.shipment.ShipmentService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * FOR-11-05 (R1, R3 to R6, R8, R9, R13.3): records and voids goods-receipt events and derives the ledger,
 * {@code acceptedQuantity}, the position status (SYSTEM edges only), shipment closure and re-delivery.
 * <p>
 * Scoping follows {@code ShipmentService}: {@link ProcurementProjectAccess#allowedProjectIds} (ADMIN
 * unrestricted); a position, receipt or project outside the caller's projects reads as 404. No DTO carries
 * money. The service is the only writer of {@code PurchasePositionEntity.acceptedQuantity}.
 */
@Service
public class GoodsReceiptService {

    /** Project-scope path of the entity (checklist step 4, same precedent as {@code ShipmentService}). */
    public static final String PROJECT_ID_PATH = "project.id";

    private static final String NOT_FOUND = "error.entity.not.found";
    private static final String NOT_RECEIVABLE = "error.goodsReceipt.notReceivable";
    private static final String MISMATCH = "error.goodsReceipt.shipment.mismatch";
    private static final Set<PositionStatus> RECEIVING = Set.of(
            PositionStatus.IN_DELIVERY, PositionStatus.PARTIALLY_RECEIVED);
    private static final String DELEGATION_REQUIRED = "error.goodsReceipt.delegation.required";
    private static final int MAX_VOID_REASON = 500;

    private final GoodsReceiptDao receiptDao;
    private final GoodsReceiptMediaDao mediaDao;
    private final GoodsReceiptLedgerService ledgerService;
    private final PurchasePositionDao positionDao;
    private final ShipmentDao shipmentDao;
    private final ShipmentLineDao lineDao;
    private final ShipmentService shipmentService;
    private final PositionLifecycleService lifecycle;
    private final ProcurementProjectAccess projectAccess;
    private final AuditLogDao auditLogDao;
    private final GoodsReceiptProperties properties;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final GoodsReceiptDelegationService delegationService;
    private final GoodsReceiptDelegationDao delegationDao;

    public GoodsReceiptService(GoodsReceiptDao receiptDao, GoodsReceiptMediaDao mediaDao,
                               GoodsReceiptLedgerService ledgerService, PurchasePositionDao positionDao,
                               ShipmentDao shipmentDao, ShipmentLineDao lineDao, ShipmentService shipmentService,
                               PositionLifecycleService lifecycle, ProcurementProjectAccess projectAccess,
                               AuditLogDao auditLogDao, GoodsReceiptProperties properties,
                               EntityManager entityManager, ApplicationEventPublisher events, Clock clock,
                               GoodsReceiptDelegationService delegationService,
                               GoodsReceiptDelegationDao delegationDao) {
        this.receiptDao = receiptDao;
        this.mediaDao = mediaDao;
        this.ledgerService = ledgerService;
        this.positionDao = positionDao;
        this.shipmentDao = shipmentDao;
        this.lineDao = lineDao;
        this.shipmentService = shipmentService;
        this.lifecycle = lifecycle;
        this.projectAccess = projectAccess;
        this.auditLogDao = auditLogDao;
        this.properties = properties;
        this.entityManager = entityManager;
        this.events = events;
        this.clock = clock;
        this.delegationService = delegationService;
        this.delegationDao = delegationDao;
    }

    // --- Record (R4) ---

    /** Records the events of one request in one transaction; see the class comment and design section 4. */
    @Transactional
    public GoodsReceiptBatchResult record(GoodsReceiptRequest request) {
        List<GoodsReceiptEventRequest> requested = request == null ? null : request.events();
        if (requested == null || requested.isEmpty()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.goodsReceipt.quantity.invalid")
                    .withField("events");
        }
        Long userId = requireUserId();
        Set<Long> allowed = allowedProjectIdsOrNull();
        boolean delegate = isDelegate();
        Set<Long> delegated = delegate ? delegationService.activeShipmentIdsFor(userId) : Set.of();
        Map<Long, Long> delegationByShipment = delegate ? delegationIdsByShipment(delegated) : Map.of();

        // Step 1: idempotency. Events whose clientRequestId is already stored are answered from the rows.
        List<GoodsReceiptEntity> resultRows = new ArrayList<>();
        Set<Long> touchedPositions = new LinkedHashSet<>();
        List<GoodsReceiptEventRequest> pending = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (GoodsReceiptEventRequest e : requested) {
            if (e == null || e.positionId() == null) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.goodsReceipt.quantity.invalid")
                        .withField("positionId");
            }
            UUID requestId = e.clientRequestId();
            if (requestId != null) {
                if (!seen.add(requestId)) {
                    continue;
                }
                List<GoodsReceiptEntity> stored = receiptDao.findByClientRequestIdOrderByPartNo(requestId);
                if (!stored.isEmpty()) {
                    requireScope(allowed, stored.get(0).getProject().getId(), stored.get(0).getId());
                    resultRows.addAll(stored);
                    touchedPositions.add(stored.get(0).getPosition().getId());
                    continue;
                }
            }
            pending.add(e);
        }

        // Step 2: lock the positions of the remaining events, ascending id.
        Map<Long, PurchasePositionEntity> positions = new LinkedHashMap<>();
        for (Long positionId : new TreeSet<>(pending.stream().map(GoodsReceiptEventRequest::positionId).toList())) {
            PurchasePositionEntity p = entityManager.find(PurchasePositionEntity.class, positionId,
                    LockModeType.PESSIMISTIC_WRITE);
            if (p == null) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, positionId);
            }
            requireScope(allowed, p.getProject().getId(), positionId);
            positions.put(positionId, p);
        }

        Map<Long, BigDecimal> released = new LinkedHashMap<>();
        Map<Long, Long> originReceipt = new HashMap<>();
        Set<Long> touchedShipments = new LinkedHashSet<>();
        List<GoodsReceiptEntity> created = new ArrayList<>();
        List<GoodsReceiptEntity> discrepancies = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Steps 3 to 5: validate, allocate and save each event in request order.
        for (GoodsReceiptEventRequest e : pending) {
            PurchasePositionEntity position = positions.get(e.positionId());
            ReceiptKind kind = e.kind();
            if (kind == null) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.goodsReceipt.quantity.invalid")
                        .withField("kind");
            }
            boolean fromAccepted = Boolean.TRUE.equals(e.fromAccepted());
            requireReceivable(position, kind, fromAccepted);
            entityManager.flush();
            Ledger before = ledgerService.ledgers(List.of(position.getId())).get(position.getId());

            Allocation alloc = allocation(position.getId());
            if (delegate) {
                // R12.4: a Delegate only works on shipments delegated to him.
                if (e.shipmentId() != null ? !delegated.contains(e.shipmentId())
                        : fromAccepted || alloc.shipments.keySet().stream().noneMatch(delegated::contains)) {
                    throw new ForemenApiException(HttpStatus.FORBIDDEN, DELEGATION_REQUIRED);
                }
                alloc.shipments.keySet().retainAll(delegated);
                alloc.unresolved.keySet().retainAll(delegated);
            }
            ShipmentEntity explicit = null;
            BigDecimal lineUnresolved = null;
            if (e.shipmentId() != null) {
                explicit = alloc.shipments.get(e.shipmentId());
                if (explicit == null || !explicit.getProject().getId().equals(position.getProject().getId())) {
                    throw new ForemenApiException(HttpStatus.BAD_REQUEST, MISMATCH).withField("shipmentId");
                }
                lineUnresolved = alloc.unresolved.get(e.shipmentId());
            }
            GoodsReceiptRules.validateEvent(kind, e.quantity(), fromAccepted, e.comment(), before,
                    fromAccepted ? null : lineUnresolved);

            List<Part> parts = split(kind, fromAccepted, e.quantity(), explicit, alloc);
            String comment = e.comment() == null || e.comment().isBlank() ? null : e.comment().trim();
            short partNo = 0;
            GoodsReceiptEntity first = null;
            Long fallbackDelegation = null;
            if (delegate) {
                Long anchor = explicit != null ? explicit.getId() : alloc.shipments.keySet().iterator().next();
                fallbackDelegation = delegationByShipment.get(anchor);
            }
            for (Part part : parts) {
                GoodsReceiptEntity row = new GoodsReceiptEntity();
                row.setProject(position.getProject());
                row.setPosition(position);
                row.setShipment(part.shipment);
                row.setKind(kind);
                row.setQuantity(part.quantity);
                row.setFromAccepted(fromAccepted);
                row.setComment(comment);
                row.setOccurredAt(now);
                row.setReceivedBy(entityManager.getReference(UserEntity.class, userId));
                row.setClientRequestId(e.clientRequestId());
                if (delegate) {
                    row.setDelegationId(part.shipment == null ? fallbackDelegation
                            : delegationByShipment.getOrDefault(part.shipment.getId(), fallbackDelegation));
                }
                row.setPartNo(partNo++);
                Long newDelegationId = row.getDelegationId();
                boolean firstUnderDelegation = newDelegationId != null
                        && !receiptDao.existsByDelegationId(newDelegationId);
                GoodsReceiptEntity saved = receiptDao.save(row);
                if (firstUnderDelegation) {
                    GoodsReceiptDelegationEntity d = delegationDao.findById(newDelegationId).orElse(null);
                    if (d != null) {
                        events.publishEvent(new ReceiptFirstDelegateEvent(newDelegationId, d.getShipmentId(),
                                d.getProjectId(), d.getDelegatedBy().getId(), userId));
                    }
                }
                if (first == null) {
                    first = saved;
                }
                created.add(saved);
                if (part.shipment != null) {
                    touchedShipments.add(part.shipment.getId());
                }
            }
            entityManager.flush();
            touchedPositions.add(position.getId());

            if (kind == ReceiptKind.MISSING || (kind == ReceiptKind.RETURNED && !fromAccepted)) {
                released.merge(position.getId(), e.quantity(), BigDecimal::add);
                originReceipt.putIfAbsent(position.getId(), first.getId());
            }
            Ledger after = ledgerService.ledgers(List.of(position.getId())).get(position.getId());
            if (kind != ReceiptKind.ACCEPTED || after.surplus().signum() > 0) {
                discrepancies.add(first);
            }
        }
        resultRows.addAll(created);

        // Step 6: acceptedQuantity and the derived position status.
        for (Long positionId : touchedPositions) {
            PurchasePositionEntity position = positions.get(positionId);
            if (position == null) {
                continue;
            }
            syncPosition(position);
        }

        // Step 7: close shipments whose lines are all resolved.
        closeResolvedShipments(touchedShipments);

        // Step 8: one re-delivery per position for the released units.
        List<Long> redeliveries = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> r : released.entrySet()) {
            Long positionId = r.getKey();
            if (receiptDao.findFifoLinesByPositionId(positionId).isEmpty()) {
                continue;
            }
            Ledger after = ledgerService.ledgers(List.of(positionId)).get(positionId);
            BigDecimal quantity = GoodsReceiptRules.redeliveryQuantity(after, r.getValue());
            if (quantity.signum() > 0) {
                redeliveries.add(shipmentService.createRedelivery(positionId, quantity,
                        originReceipt.get(positionId)));
            }
        }

        // Step 9: discrepancy events and audit.
        for (GoodsReceiptEntity row : discrepancies) {
            events.publishEvent(new GoodsReceiptDiscrepancyEvent(
                    row.getPosition().getId(), row.getProject().getId(), row.getId(), userId));
        }
        for (GoodsReceiptEntity row : created) {
            saveAudit(null, snapshot(row), row, "CREATE");
        }
        entityManager.flush();
        return new GoodsReceiptBatchResult(toDtos(resultRows), positionResults(touchedPositions), redeliveries);
    }

    // --- Void (R5) ---

    /** Voids one event, recomputes the ledger, {@code acceptedQuantity} and the status. Never notifies. */
    @Transactional
    public GoodsReceiptVoidResult voidReceipt(Long id, GoodsReceiptVoidRequest request) {
        GoodsReceiptEntity receipt = load(id);
        String reason = request == null || request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.goodsReceipt.comment.required")
                    .withField("reason");
        }
        Long userId = requireUserId();
        PurchasePositionEntity position = entityManager.find(PurchasePositionEntity.class,
                receipt.getPosition().getId(), LockModeType.PESSIMISTIC_WRITE);
        if (receipt.getVoidedAt() != null) {
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.goodsReceipt.alreadyVoided");
        }
        if (!canVoid(receipt)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.goodsReceipt.void.forbidden");
        }
        String before = snapshot(receipt);
        receipt.setVoidedAt(OffsetDateTime.now(clock));
        receipt.setVoidedBy(entityManager.getReference(UserEntity.class, userId));
        receipt.setVoidReason(reason.length() > MAX_VOID_REASON ? reason.substring(0, MAX_VOID_REASON) : reason);
        receiptDao.save(receipt);
        entityManager.flush();

        Ledger ledger = ledgerService.ledgers(List.of(position.getId())).get(position.getId());
        if (ledger.acceptedNet().signum() < 0) {
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.goodsReceipt.returnExceedsAccepted");
        }
        position.setAcceptedQuantity(ledger.acceptedNet());
        positionDao.save(position);

        boolean redeliveryKept = false;
        for (ShipmentEntity s : shipmentDao.findByOriginReceiptId(receipt.getId())) {
            if (s.getStatus() == ShipmentStatus.CANCELLED) {
                continue;
            }
            if (isUnchangedRedelivery(s) && !receiptDao.existsActiveByShipmentId(s.getId())) {
                shipmentService.cancel(s.getId());
            } else {
                redeliveryKept = true;
            }
        }
        entityManager.flush();
        syncPosition(position);
        saveAudit(before, snapshot(receipt), receipt, "UPDATE");
        entityManager.flush();
        return new GoodsReceiptVoidResult(toDtos(List.of(receipt)).get(0), positionResult(position.getId()),
                redeliveryKept);
    }

    // --- Reads (R9) ---

    @Transactional(readOnly = true)
    public Page<GoodsReceiptDto> list(GoodsReceiptFilter filter, Pageable pageable) {
        Set<Long> allowed = allowedProjectIdsOrNull();
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        if (allowed != null && allowed.isEmpty()) {
            return new PageImpl<>(List.of(), sorted, 0);
        }
        Specification<GoodsReceiptEntity> spec = specification(filter, allowed);
        if (isDelegate()) {
            spec = spec.and(delegateVisible(requireUserId(), delegatedIds()));
        }
        Page<GoodsReceiptEntity> page = receiptDao.findAll(spec, sorted);
        return new PageImpl<>(toDtos(page.getContent()), sorted, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public GoodsReceiptDto get(Long id) {
        GoodsReceiptEntity receipt = load(id);
        if (isDelegate() && !delegateMayRead(receipt, requireUserId(), delegatedIds())) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, id);
        }
        return toDtos(List.of(receipt)).get(0);
    }

    /** Ledger of one position (planner and kanban drawers). */
    @Transactional(readOnly = true)
    public PositionLedgerDto ledger(Long positionId) {
        PurchasePositionEntity p = positionId == null ? null : positionDao.findById(positionId).orElse(null);
        if (p == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, positionId);
        }
        requireScope(allowedProjectIdsOrNull(), p.getProject().getId(), positionId);
        if (isDelegate()) {
            Long userId = requireUserId();
            Set<Long> delegated = delegatedIds();
            boolean visible = receiptDao.findNonCancelledLinesByPositionIds(List.of(positionId)).stream()
                    .anyMatch(l -> delegated.contains(l.getShipment().getId()))
                    || receiptDao.findByPositionIdOrderByOccurredAtAscIdAsc(positionId).stream()
                    .anyMatch(r -> r.getReceivedBy() != null && userId.equals(r.getReceivedBy().getId()));
            if (!visible) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, positionId);
            }
        }
        return positionResult(positionId);
    }

    /** Positions awaiting receipt with ledger and shipments; delivered shipments first, then ETA, nulls last. */
    @Transactional(readOnly = true)
    public List<ToReceiveDto> toReceive(Long projectId) {
        Set<Long> allowed = allowedProjectIdsOrNull();
        if (projectId != null && allowed != null && !allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, projectId);
        }
        if (allowed != null && allowed.isEmpty()) {
            return List.of();
        }
        Specification<PurchasePositionEntity> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(root.get("status").in(RECEIVING));
            if (projectId != null) {
                ps.add(cb.equal(root.get("project").get("id"), projectId));
            }
            if (allowed != null) {
                ps.add(root.get("project").get("id").in(allowed));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        List<PurchasePositionEntity> positions = positionDao.findAll(spec, Sort.by("id"));
        if (positions.isEmpty()) {
            return List.of();
        }
        List<Long> ids = positions.stream().map(PurchasePositionEntity::getId).toList();
        Map<Long, Ledger> ledgers = ledgerService.ledgers(ids);
        boolean delegate = isDelegate();
        Set<Long> delegated = delegate ? delegatedIds() : Set.of();
        List<ShipmentLineEntity> lines = receiptDao.findNonCancelledLinesByPositionIds(ids).stream()
                .filter(l -> !delegate || delegated.contains(l.getShipment().getId())).toList();
        Map<Long, GoodsReceiptDelegationEntity> delegations = activeDelegationsByShipment(
                lines.stream().map(l -> l.getShipment().getId()).collect(java.util.stream.Collectors.toSet()));
        Map<String, BigDecimal> resolved = resolvedByShipmentAndPosition(
                lines.stream().map(l -> l.getShipment().getId()).collect(java.util.stream.Collectors.toSet()));
        Map<Long, Map<Long, ToReceiveDto.Shipment>> byPosition = new HashMap<>();
        for (ShipmentLineEntity l : lines) {
            ShipmentEntity s = l.getShipment();
            Long positionId = l.getPosition().getId();
            ToReceiveDto.Shipment existing = byPosition.computeIfAbsent(positionId, k -> new LinkedHashMap<>())
                    .get(s.getId());
            BigDecimal quantity = (existing == null ? BigDecimal.ZERO : existing.quantity()).add(l.getQuantity());
            BigDecimal unresolved = quantity
                    .subtract(resolved.getOrDefault(key(s.getId(), positionId), BigDecimal.ZERO))
                    .max(BigDecimal.ZERO);
            GoodsReceiptDelegationEntity d = delegations.get(s.getId());
            byPosition.get(positionId).put(s.getId(), new ToReceiveDto.Shipment(
                    s.getId(), s.getStatus(), s.getEtaDate(), s.getCarrierCode(), quantity, unresolved,
                    d == null || d.getAssignee() == null ? null : d.getAssignee().getName(),
                    d == null ? null : d.getId()));
        }
        Comparator<ToReceiveDto.Shipment> shipmentOrder = Comparator
                .comparing((ToReceiveDto.Shipment s) -> s.status() == ShipmentStatus.DELIVERED ? 0 : 1)
                .thenComparing(ToReceiveDto.Shipment::etaDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ToReceiveDto.Shipment::shipmentId);
        boolean pl = isPl();
        List<ToReceiveDto> result = new ArrayList<>();
        for (PurchasePositionEntity p : positions) {
            if (delegate && !byPosition.containsKey(p.getId())) {
                continue;
            }
            List<ToReceiveDto.Shipment> shipments = new ArrayList<>(
                    byPosition.getOrDefault(p.getId(), Map.of()).values());
            shipments.sort(shipmentOrder);
            result.add(new ToReceiveDto(p.getId(), p.getProject().getId(), materialLabel(p, pl), unitName(p, pl),
                    p.getStatus(), toDto(ledgers.get(p.getId())), shipments));
        }
        Comparator<ToReceiveDto> order = Comparator
                .comparing((ToReceiveDto d) -> d.shipments().isEmpty()
                        || d.shipments().get(0).status() != ShipmentStatus.DELIVERED ? 1 : 0)
                .thenComparing(d -> d.shipments().isEmpty() ? null : d.shipments().get(0).etaDate(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ToReceiveDto::positionId);
        result.sort(order);
        return result;
    }

    // --- Allocation (design section 4, step 4) ---

    /** Non-cancelled shipments of a position in FIFO order and the unresolved line quantity of each. */
    private static final class Allocation {
        final Map<Long, ShipmentEntity> shipments = new LinkedHashMap<>();
        final Map<Long, BigDecimal> unresolved = new LinkedHashMap<>();
    }

    private record Part(ShipmentEntity shipment, BigDecimal quantity) {
    }

    private Allocation allocation(Long positionId) {
        Allocation a = new Allocation();
        Map<Long, BigDecimal> lineQuantity = new LinkedHashMap<>();
        for (ShipmentLineEntity l : receiptDao.findFifoLinesByPositionId(positionId)) {
            ShipmentEntity s = l.getShipment();
            a.shipments.putIfAbsent(s.getId(), s);
            lineQuantity.merge(s.getId(), l.getQuantity(), BigDecimal::add);
        }
        Map<String, BigDecimal> resolved = resolvedByShipmentAndPosition(lineQuantity.keySet());
        lineQuantity.forEach((shipmentId, quantity) -> a.unresolved.put(shipmentId,
                quantity.subtract(resolved.getOrDefault(key(shipmentId, positionId), BigDecimal.ZERO))
                        .max(BigDecimal.ZERO)));
        return a;
    }

    private List<Part> split(ReceiptKind kind, boolean fromAccepted, BigDecimal quantity, ShipmentEntity explicit,
                             Allocation alloc) {
        if (fromAccepted) {
            return List.of(new Part(null, quantity));
        }
        if (explicit != null) {
            return List.of(new Part(explicit, quantity));
        }
        List<Part> parts = new ArrayList<>();
        BigDecimal remaining = quantity;
        for (Map.Entry<Long, BigDecimal> line : alloc.unresolved.entrySet()) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal take = remaining.min(line.getValue());
            if (take.signum() > 0) {
                parts.add(new Part(alloc.shipments.get(line.getKey()), take));
                remaining = remaining.subtract(take);
            }
        }
        if (remaining.signum() > 0) {
            parts.add(new Part(null, remaining));
        }
        return parts;
    }

    /** Resolved quantities (accepted + missing + door returns, non-voided) per {@code shipmentId:positionId}. */
    private Map<String, BigDecimal> resolvedByShipmentAndPosition(Collection<Long> shipmentIds) {
        Map<String, BigDecimal> result = new HashMap<>();
        if (shipmentIds == null || shipmentIds.isEmpty()) {
            return result;
        }
        for (Object[] row : receiptDao.sumsByShipmentAndPosition(shipmentIds)) {
            result.merge(key(((Number) row[0]).longValue(), ((Number) row[1]).longValue()),
                    (BigDecimal) row[3], BigDecimal::add);
        }
        return result;
    }

    private static String key(Long shipmentId, Long positionId) {
        return shipmentId + ":" + positionId;
    }

    // --- Status, acceptedQuantity and closure ---

    /** Writes {@code acceptedQuantity = acceptedNet} and derives the status through SYSTEM transitions. */
    private void syncPosition(PurchasePositionEntity position) {
        Ledger ledger = ledgerService.ledgers(List.of(position.getId())).get(position.getId());
        position.setAcceptedQuantity(ledger.acceptedNet());
        positionDao.save(position);
        PositionStatus current = position.getStatus();
        if (current != PositionStatus.IN_DELIVERY && current != PositionStatus.PARTIALLY_RECEIVED
                && current != PositionStatus.RECEIVED) {
            return;
        }
        PositionStatus target = GoodsReceiptRules.deriveStatus(ledger,
                receiptDao.existsActiveByPositionId(position.getId()));
        if (target == current) {
            return;
        }
        // PositionTransitionPolicy has no SYSTEM edge RECEIVED -> IN_DELIVERY: step through PARTIALLY_RECEIVED.
        if (current == PositionStatus.RECEIVED && target == PositionStatus.IN_DELIVERY) {
            lifecycle.transition(position.getId(), PositionStatus.PARTIALLY_RECEIVED, Actor.SYSTEM);
        }
        lifecycle.transition(position.getId(), target, Actor.SYSTEM);
    }

    private void closeResolvedShipments(Set<Long> shipmentIds) {
        if (shipmentIds.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> resolved = resolvedByShipmentAndPosition(shipmentIds);
        for (Long shipmentId : shipmentIds) {
            ShipmentEntity shipment = shipmentDao.findById(shipmentId).orElse(null);
            if (shipment == null || shipment.getStatus().isTerminal()) {
                continue;
            }
            Map<Long, BigDecimal> perPosition = new LinkedHashMap<>();
            for (ShipmentLineEntity l : lineDao.findByShipmentId(shipmentId)) {
                perPosition.merge(l.getPosition().getId(), l.getQuantity(), BigDecimal::add);
            }
            boolean allResolved = !perPosition.isEmpty();
            for (Map.Entry<Long, BigDecimal> line : perPosition.entrySet()) {
                // The per-shipment sums merge accepted, missing and door returns; lineResolved sums them again.
                BigDecimal done = resolved.getOrDefault(key(shipmentId, line.getKey()), BigDecimal.ZERO);
                if (!GoodsReceiptRules.lineResolved(line.getValue(), done, BigDecimal.ZERO, BigDecimal.ZERO)) {
                    allResolved = false;
                    break;
                }
            }
            if (allResolved) {
                shipmentService.closeByReceipt(shipmentId);
                delegationService.completeForShipment(shipmentId);
            }
        }
    }

    /** The system re-delivery as created: CREATED, shop delivery, nothing filled in, a single line. */
    private boolean isUnchangedRedelivery(ShipmentEntity s) {
        return s.getStatus() == ShipmentStatus.CREATED
                && s.getDeliveryMethod() == com.foremen.dao.model.DeliveryMethod.SHOP_DELIVERY
                && isBlank(s.getTrackingNumber()) && isBlank(s.getTrackingUrl()) && isBlank(s.getShopOrderLink())
                && isBlank(s.getCarrierCode()) && isBlank(s.getDriverName()) && isBlank(s.getDriverPhone())
                && s.getEtaDate() == null && s.getEtaTimeFrom() == null && s.getEtaTimeTo() == null
                && lineDao.findByShipmentId(s.getId()).size() == 1;
    }

    private void requireReceivable(PurchasePositionEntity position, ReceiptKind kind, boolean fromAccepted) {
        PositionStatus status = position.getStatus();
        boolean laterComplaint = status == PositionStatus.RECEIVED && kind == ReceiptKind.RETURNED && fromAccepted;
        if (!RECEIVING.contains(status) && !laterComplaint) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_RECEIVABLE);
        }
    }

    // --- Scope and caller ---

    private GoodsReceiptEntity load(Long id) {
        GoodsReceiptEntity r = id == null ? null : receiptDao.findById(id).orElse(null);
        if (r == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, id);
        }
        requireScope(allowedProjectIdsOrNull(), r.getProject().getId(), id);
        return r;
    }

    private static void requireScope(Set<Long> allowed, Long projectId, Object entityId) {
        if (allowed != null && !allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, entityId);
        }
    }

    /** {@code null} = unrestricted (ADMIN, or no authentication context). */
    private Set<Long> allowedProjectIdsOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || roles(auth).contains("ADMIN")) {
            return null;
        }
        Long userId = parseUserId(auth);
        return userId == null ? Set.of() : projectAccess.allowedProjectIds(userId);
    }

    private static Long requireUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = auth == null || !auth.isAuthenticated() ? null : parseUserId(auth);
        if (userId == null) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
        }
        return userId;
    }

    private static Long parseUserId(Authentication auth) {
        try {
            return Long.parseLong(auth.getName().trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Set<String> roles(Authentication auth) {
        Set<String> roles = new HashSet<>();
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            roles.add(a != null && a.startsWith("ROLE_") ? a.substring(5) : a);
        }
        return roles;
    }

    /** R12.4: a caller whose only procurement role is WORKER is a Delegate (ADMIN and staff roles never are). */
    private static boolean isDelegate() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        Set<String> roles = roles(auth);
        return roles.contains("WORKER") && !roles.contains("ADMIN") && !roles.contains("MANAGER")
                && !roles.contains("FOREMAN") && !roles.contains("BUYER");
    }

    private Set<Long> delegatedIds() {
        return delegationService.activeShipmentIdsFor(requireUserId());
    }

    private Map<Long, Long> delegationIdsByShipment(Collection<Long> shipmentIds) {
        Map<Long, Long> result = new HashMap<>();
        activeDelegationsByShipment(shipmentIds).forEach((shipmentId, d) -> result.put(shipmentId, d.getId()));
        return result;
    }

    private Map<Long, GoodsReceiptDelegationEntity> activeDelegationsByShipment(Collection<Long> shipmentIds) {
        Map<Long, GoodsReceiptDelegationEntity> result = new HashMap<>();
        if (shipmentIds == null || shipmentIds.isEmpty()) {
            return result;
        }
        for (GoodsReceiptDelegationEntity d : delegationDao.findActiveByShipmentIds(shipmentIds)) {
            result.put(d.getShipmentId(), d);
        }
        return result;
    }

    /** R12.4: events of delegated shipments, plus the Delegate's own events. */
    private static boolean delegateMayRead(GoodsReceiptEntity r, Long userId, Set<Long> delegated) {
        return (r.getReceivedBy() != null && userId.equals(r.getReceivedBy().getId()))
                || (r.getShipment() != null && delegated.contains(r.getShipment().getId()));
    }

    private static Specification<GoodsReceiptEntity> delegateVisible(Long userId, Set<Long> delegated) {
        return (root, query, cb) -> {
            Predicate own = cb.equal(root.get("receivedBy").get("id"), userId);
            return delegated.isEmpty() ? own : cb.or(own, root.get("shipment").get("id").in(delegated));
        };
    }

    private boolean canVoid(GoodsReceiptEntity r) {
        if (r.getVoidedAt() != null) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return GoodsReceiptRules.canVoid(roles(auth), r.getReceivedBy() == null ? null : r.getReceivedBy().getId(),
                parseUserId(auth), r.getOccurredAt() == null ? null : r.getOccurredAt().toInstant(),
                clock.instant(), properties.voidWindow());
    }

    // --- Specification and mapping ---

    private Specification<GoodsReceiptEntity> specification(GoodsReceiptFilter f, Set<Long> allowed) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (allowed != null) {
                ps.add(root.get("project").get("id").in(allowed));
            }
            boolean includeVoided = f != null && Boolean.TRUE.equals(f.includeVoided());
            if (!includeVoided) {
                ps.add(cb.isNull(root.get("voidedAt")));
            }
            if (f != null) {
                if (f.projectId() != null) {
                    ps.add(cb.equal(root.get("project").get("id"), f.projectId()));
                }
                if (f.positionId() != null) {
                    ps.add(cb.equal(root.get("position").get("id"), f.positionId()));
                }
                if (f.shipmentId() != null) {
                    ps.add(cb.equal(root.get("shipment").get("id"), f.shipmentId()));
                }
                if (f.kind() != null) {
                    ps.add(cb.equal(root.get("kind"), f.kind()));
                }
                if (f.from() != null) {
                    ps.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), f.from()));
                }
                if (f.to() != null) {
                    ps.add(cb.lessThanOrEqualTo(root.get("occurredAt"), f.to()));
                }
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private List<GoodsReceiptDto> toDtos(List<GoodsReceiptEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<GoodsReceiptMediaDto>> media = new HashMap<>();
        for (GoodsReceiptMediaEntity m : mediaDao.findByReceiptIds(rows.stream().map(GoodsReceiptEntity::getId).toList())) {
            media.computeIfAbsent(m.getReceipt().getId(), k -> new ArrayList<>())
                    .add(new GoodsReceiptMediaDto(m.getId(), m.getFileName(), m.getSizeBytes(), m.getContentType()));
        }
        boolean pl = isPl();
        Set<Long> delegationIds = new HashSet<>();
        for (GoodsReceiptEntity r : rows) {
            if (r.getDelegationId() != null) {
                delegationIds.add(r.getDelegationId());
            }
        }
        Map<Long, GoodsReceiptDelegationEntity> delegationsById = new HashMap<>();
        if (!delegationIds.isEmpty()) {
            delegationDao.findAllById(delegationIds).forEach(d -> delegationsById.put(d.getId(), d));
        }
        List<GoodsReceiptDto> result = new ArrayList<>();
        for (GoodsReceiptEntity r : rows) {
            GoodsReceiptDelegationEntity d = r.getDelegationId() == null ? null
                    : delegationsById.get(r.getDelegationId());
            PurchasePositionEntity p = r.getPosition();
            boolean allowedToChange = canVoid(r);
            result.add(new GoodsReceiptDto(r.getId(), r.getProject().getId(), p.getId(), materialLabel(p, pl),
                    unitName(p, pl), r.getShipment() == null ? null : r.getShipment().getId(), r.getKind(),
                    r.getQuantity(), r.isFromAccepted(), r.getComment(), r.getOccurredAt(),
                    r.getReceivedBy() == null ? null : r.getReceivedBy().getId(),
                    r.getReceivedBy() == null ? null : r.getReceivedBy().getName(),
                    r.getVoidedAt(), r.getVoidedBy() == null ? null : r.getVoidedBy().getName(), r.getVoidReason(),
                    media.getOrDefault(r.getId(), List.of()), allowedToChange, allowedToChange,
                    r.getDelegationId(),
                    d == null || d.getDelegatedBy() == null ? null : d.getDelegatedBy().getName()));
        }
        return result;
    }

    private List<PositionLedgerDto> positionResults(Collection<Long> positionIds) {
        Map<Long, Ledger> ledgers = ledgerService.ledgers(positionIds);
        List<PositionLedgerDto> result = new ArrayList<>();
        for (Long id : positionIds) {
            PurchasePositionEntity p = positionDao.findById(id).orElse(null);
            Ledger l = ledgers.get(id);
            if (p != null && l != null) {
                result.add(new PositionLedgerDto(id, p.getStatus(), toDto(l)));
            }
        }
        return result;
    }

    private PositionLedgerDto positionResult(Long positionId) {
        List<PositionLedgerDto> r = positionResults(List.of(positionId));
        if (r.isEmpty()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, positionId);
        }
        return r.get(0);
    }

    static LedgerDto toDto(Ledger l) {
        return new LedgerDto(l.cap(), l.acceptedNet(), l.missing(), l.returned(), l.outstanding(), l.surplus(),
                l.inFlight(), l.committed(), l.remainder());
    }

    private static boolean isPl() {
        return !"ru".equalsIgnoreCase(LocaleContextHolder.getLocale().getLanguage());
    }

    private static String materialLabel(PurchasePositionEntity p, boolean pl) {
        return com.foremen.service.procurement.PositionLabel.of(p, pl);
    }

    private static String unitName(PurchasePositionEntity p, boolean pl) {
        return p.getUnit() == null ? null : pl ? p.getUnit().getNamePL() : p.getUnit().getNameRU();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // --- Audit (R13.3): ids, kind and quantity only; no free text ---

    private String snapshot(GoodsReceiptEntity r) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", r.getId());
        snap.put("projectId", r.getProject().getId());
        snap.put("positionId", r.getPosition().getId());
        snap.put("shipmentId", r.getShipment() == null ? null : r.getShipment().getId());
        snap.put("kind", r.getKind());
        snap.put("quantity", r.getQuantity());
        snap.put("fromAccepted", r.isFromAccepted());
        snap.put("voided", r.getVoidedAt() != null);
        try {
            return AdminService.AUDIT_OBJECT_MAPPER.writeValueAsString(snap);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize receipt audit snapshot", ex);
        }
    }

    private void saveAudit(String before, String after, GoodsReceiptEntity r, String operation) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuditLogEntity log = new AuditLogEntity();
        log.setEntityClass(GoodsReceiptEntity.class.getSimpleName());
        log.setEntityId(r.getId());
        log.setOperation(operation);
        log.setPerformedBy(auth != null && auth.isAuthenticated() ? auth.getName() : "SYSTEM");
        log.setPerformedAt(LocalDateTime.now(clock));
        log.setSnapshotBefore(before);
        log.setSnapshotAfter(after);
        auditLogDao.save(log);
    }
}
