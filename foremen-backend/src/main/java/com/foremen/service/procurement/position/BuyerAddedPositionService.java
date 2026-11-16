package com.foremen.service.procurement.position;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.controller.model.BuyerAddedPositionRequest;
import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.PurchaseRequestService;
import com.foremen.service.PurchasePositionService;
import com.foremen.service.model.ExternalOrderLineServiceModel;
import com.foremen.service.model.ExternalOrderServiceExtendedModel;
import com.foremen.service.model.PurchasePositionServiceExtendedModel;
import com.foremen.service.procurement.externalorder.ExternalOrderService;

/**
 * FOR-11-06 (R10.1-R10.3, R10.6): creates a buyer-added position. The position is a normal {@code AGREED}
 * position with {@code added_by_buyer} and {@code client_unpaid} set; the system moves it
 * {@code PLANNED -> WITH_BUYER} through the flag-guarded edge, optionally attaches it to a DRAFT external
 * order or to a purchase request, and announces {@link PositionAddedByBuyerEvent}. The generic create
 * and the proforma-paid gate are not touched.
 *
 * <p>Only BUYER, MANAGER and ADMIN may call it ({@value #FORBIDDEN}). Project scope, the ACTIVE project
 * rule and the field checks are those of {@link PurchasePositionService#validateCreate}.
 */
@Service
public class BuyerAddedPositionService {

    static final String FORBIDDEN = "error.purchasePosition.buyerAdded.forbidden";

    private final PurchasePositionService positionService;
    private final PositionLifecycleService lifecycleService;
    private final ExternalOrderService externalOrderService;
    private final PurchaseRequestService purchaseRequestService;
    private final ApplicationEventPublisher events;

    public BuyerAddedPositionService(PurchasePositionService positionService,
                                     PositionLifecycleService lifecycleService,
                                     ExternalOrderService externalOrderService,
                                     PurchaseRequestService purchaseRequestService,
                                     ApplicationEventPublisher events) {
        this.positionService = positionService;
        this.lifecycleService = lifecycleService;
        this.externalOrderService = externalOrderService;
        this.purchaseRequestService = purchaseRequestService;
        this.events = events;
    }

    @Transactional
    public PurchasePositionServiceExtendedModel create(BuyerAddedPositionRequest request) {
        if (!hasAnyAuthority("BUYER", "MANAGER", "ADMIN")) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }

        PurchasePositionServiceExtendedModel model = new PurchasePositionServiceExtendedModel();
        model.setProjectId(request.projectId());
        model.setKind(PositionKind.FINISHING);
        model.setSourcingPath(SourcingPath.AGREED);
        model.setPlannedMaterialId(request.plannedMaterialId());
        model.setQuantity(request.quantity());
        model.setUnitId(request.unitId());
        model.setPlannedPurchasePrice(request.plannedPurchasePrice());
        model.setSalePrice(request.salePrice());
        model.setPurchaseVatRateId(request.purchaseVatRateId());
        model.setSaleVatRateId(request.saleVatRateId());
        model.setNotes(request.note());
        PurchasePositionServiceExtendedModel created = positionService.create(model);
        Long positionId = created.getId();

        PurchasePositionEntity entity = positionService.getDao().findById(positionId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, PositionLifecycleService.NOT_FOUND));
        entity.setAddedByBuyer(true);
        entity.setClientUnpaid(true);
        positionService.getDao().save(entity);
        positionService.getEntityManager().flush();

        lifecycleService.transitionBuyerAdded(positionId);

        if (request.externalOrderId() != null) {
            attachToOrder(request.externalOrderId(), created);
        }
        if (request.purchaseRequestId() != null) {
            purchaseRequestService.attachPositions(request.purchaseRequestId(), List.of(positionId));
        }

        events.publishEvent(new PositionAddedByBuyerEvent(positionId, request.projectId(), currentUserId()));
        return positionService.findById(positionId);
    }

    /** Appends one line (planned purchase price) to the DRAFT order; the order service re-checks every rule. */
    private void attachToOrder(Long orderId, PurchasePositionServiceExtendedModel position) {
        ExternalOrderServiceExtendedModel order = externalOrderService.findById(orderId);
        List<ExternalOrderLineServiceModel> lines = new ArrayList<>();
        if (order.getLines() != null) {
            lines.addAll(order.getLines());
        }
        ExternalOrderLineServiceModel line = new ExternalOrderLineServiceModel();
        line.setPositionId(position.getId());
        line.setQuantity(position.getQuantity());
        line.setUnitPurchaseNet(position.getPlannedPurchasePrice());
        lines.add(line);
        externalOrderService.replaceLines(orderId, lines);
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        try {
            return Long.parseLong(auth.getName().trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasAnyAuthority(String... codes) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            for (String code : codes) {
                if (("ROLE_" + code).equals(ga.getAuthority()) || code.equals(ga.getAuthority())) {
                    return true;
                }
            }
        }
        return false;
    }
}
