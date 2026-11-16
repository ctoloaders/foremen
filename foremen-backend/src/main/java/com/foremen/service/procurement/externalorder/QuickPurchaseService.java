package com.foremen.service.procurement.externalorder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.model.ExternalOrderLineServiceModel;
import com.foremen.service.model.ExternalOrderServiceExtendedModel;
import com.foremen.service.procurement.PositionLabel;
import com.foremen.service.procurement.access.ProcurementProjectAccess;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * FOR-11-06 R11: turns a selection of {@code WITH_BUYER} positions of ONE project into {@code DRAFT}
 * external orders, one per seller, in one transaction (all or nothing). Orders only: a shipment cannot
 * exist before the supplier is paid. Lines go through {@link ExternalOrderService#create}, so every
 * order rule (coverage, ceiling, VAT, delivery allocation, audit) is the one of the draft editor.
 */
@Service
public class QuickPurchaseService {

    static final String MIXED_PROJECTS = "error.quickPurchase.mixedProjects";
    static final String SELLER_REQUIRED = "error.quickPurchase.sellerRequired";
    static final String PRICE_REQUIRED = "error.quickPurchase.priceRequired";
    static final String POSITIONS_INVALID = "error.quickPurchase.positions.invalid";

    static final int MAX_POSITIONS = 200;

    private final PurchasePositionDao positionDao;
    private final ExternalOrderLineDao lineDao;
    private final ExternalOrderService orderService;
    private final ProcurementProjectAccess projectAccess;
    private final EntityManager entityManager;

    public QuickPurchaseService(PurchasePositionDao positionDao, ExternalOrderLineDao lineDao,
                                ExternalOrderService orderService, ProcurementProjectAccess projectAccess,
                                EntityManager entityManager) {
        this.positionDao = positionDao;
        this.lineDao = lineDao;
        this.orderService = orderService;
        this.projectAccess = projectAccess;
        this.entityManager = entityManager;
    }

    /** Shared header; the delivery cost is placed on the first created order only (it is one cost). */
    public record Header(String referenceNumber, LocalDate expectedShipDate, LocalDate expectedDeliveryDate,
                         BigDecimal deliveryCostNet, Long deliveryVatRateId, String note) {
    }

    /**
     * @param positionIds              positions to buy (distinct, one project)
     * @param sellerByPosition         optional seller per position
     * @param defaultSellerId          optional seller for positions without an own one
     * @param acknowledgedPositionIds  positions whose price above the ceiling the buyer acknowledged
     */
    public record Request(List<Long> positionIds, Map<Long, Long> sellerByPosition, Long defaultSellerId,
                          Set<Long> acknowledgedPositionIds, Header header) {
    }

    public record OrderRef(Long id, Long sellerId, int lineCount) {
    }

    public record Result(List<OrderRef> orders) {
    }

    @Transactional
    public Result createOrders(Request request) {
        List<Long> ids = request == null || request.positionIds() == null ? List.of() : request.positionIds();
        if (ids.isEmpty() || ids.size() > MAX_POSITIONS || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(Objects::isNull)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, POSITIONS_INVALID);
        }
        List<PurchasePositionEntity> positions = loadLocked(ids);

        Long projectId = positions.get(0).getProject().getId();
        if (positions.stream().anyMatch(p -> !Objects.equals(p.getProject().getId(), projectId))) {
            throw new ForemenApiException(HttpStatus.CONFLICT, MIXED_PROJECTS);
        }
        requireProjectAccess(projectId);

        Map<Long, Long> sellerByPosition = request.sellerByPosition() == null ? Map.of() : request.sellerByPosition();
        Set<Long> acknowledged = request.acknowledgedPositionIds() == null ? Set.of() : request.acknowledgedPositionIds();

        Map<Long, List<PurchasePositionEntity>> groups = new LinkedHashMap<>();
        Set<Long> aboveCeiling = new TreeSet<>();
        for (PurchasePositionEntity p : positions) {
            if (p.getPlannedMaterial() == null) {
                throw conflict(PositionLabel.MATERIAL_REQUIRED, p.getId());
            }
            if (p.getStatus() != PositionStatus.WITH_BUYER || lineDao.existsLiveByPositionId(p.getId())) {
                throw conflict(ExternalOrderService.POSITION_NOT_PURCHASABLE, p.getId());
            }
            if (p.getPlannedPurchasePrice() == null || p.getPlannedPurchasePrice().signum() < 0) {
                throw conflict(PRICE_REQUIRED, p.getId());
            }
            Long sellerId = sellerOf(p, sellerByPosition, request.defaultSellerId());
            if (sellerId == null) {
                throw conflict(SELLER_REQUIRED, p.getId());
            }
            if (CeilingCheck.exceeds(unitNet(p), p.getSalePrice()) && !acknowledged.contains(p.getId())) {
                aboveCeiling.add(p.getId());
            }
            groups.computeIfAbsent(sellerId, k -> new ArrayList<>()).add(p);
        }
        if (!aboveCeiling.isEmpty()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ExternalOrderService.ABOVE_CEILING,
                    aboveCeiling.iterator().next())
                    .withParams(Map.of("ids", List.copyOf(aboveCeiling)));
        }

        List<OrderRef> created = new ArrayList<>(groups.size());
        boolean first = true;
        for (Map.Entry<Long, List<PurchasePositionEntity>> group : groups.entrySet()) {
            ExternalOrderServiceExtendedModel model = new ExternalOrderServiceExtendedModel();
            model.setProjectId(projectId);
            model.setSellerId(group.getKey());
            applyHeader(model, request.header(), first);
            model.setLines(group.getValue().stream().map(p -> line(p, acknowledged)).toList());
            ExternalOrderServiceExtendedModel order = orderService.create(model);
            created.add(new OrderRef(order.getId(), group.getKey(), group.getValue().size()));
            first = false;
        }
        return new Result(created);
    }

    private List<PurchasePositionEntity> loadLocked(List<Long> ids) {
        Map<Long, PurchasePositionEntity> byId = new LinkedHashMap<>();
        positionDao.findAllByIdIn(ids).forEach(p -> byId.put(p.getId(), p));
        for (Long id : ids) {
            if (!byId.containsKey(id)) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, ExternalOrderService.POSITION_NOT_FOUND, id);
            }
        }
        List<PurchasePositionEntity> sorted = new ArrayList<>(byId.values());
        sorted.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        for (PurchasePositionEntity p : sorted) {
            entityManager.lock(p, LockModeType.PESSIMISTIC_WRITE);
        }
        return sorted;
    }

    /** Supplied per-position seller, else the default seller, else the planned material's seller. */
    private static Long sellerOf(PurchasePositionEntity p, Map<Long, Long> byPosition, Long defaultSellerId) {
        Long own = byPosition.get(p.getId());
        if (own != null) {
            return own;
        }
        if (defaultSellerId != null) {
            return defaultSellerId;
        }
        FinishingMaterialEntity material = p.getPlannedMaterial();
        MaterialSellerEntity seller = material == null ? null : material.getSeller();
        return seller == null ? null : seller.getId();
    }

    private static BigDecimal unitNet(PurchasePositionEntity p) {
        return p.getPlannedPurchasePrice().setScale(2, RoundingMode.HALF_UP);
    }

    private static ExternalOrderLineServiceModel line(PurchasePositionEntity p, Set<Long> acknowledged) {
        ExternalOrderLineServiceModel l = new ExternalOrderLineServiceModel();
        l.setPositionId(p.getId());
        l.setQuantity(p.getQuantity());
        l.setUnitPurchaseNet(unitNet(p));
        l.setPurchaseVatRateId(p.getPurchaseVatRate() == null ? null : p.getPurchaseVatRate().getId());
        l.setAboveCeilingAcknowledged(acknowledged.contains(p.getId()));
        return l;
    }

    private static void applyHeader(ExternalOrderServiceExtendedModel model, Header h, boolean withCost) {
        if (h == null) {
            return;
        }
        model.setReferenceNumber(h.referenceNumber());
        model.setExpectedShipDate(h.expectedShipDate());
        model.setExpectedDeliveryDate(h.expectedDeliveryDate());
        model.setNote(h.note());
        if (withCost) {
            model.setDeliveryCostNet(h.deliveryCostNet());
            model.setDeliveryVatRateId(h.deliveryVatRateId());
        }
    }

    /** Non-ADMIN callers reach only their allowed projects; denial is the usual 404. */
    private void requireProjectAccess(Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ExternalOrderService.ENTITY_NOT_FOUND, projectId);
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
            userId = null;
        }
        Set<Long> allowed = userId == null ? null : projectAccess.allowedProjectIds(userId);
        if (allowed == null || !allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ExternalOrderService.ENTITY_NOT_FOUND, projectId);
        }
    }

    private static ForemenApiException conflict(String key, Long id) {
        return new ForemenApiException(HttpStatus.CONFLICT, key, id).withParams(Map.of("id", id));
    }
}
