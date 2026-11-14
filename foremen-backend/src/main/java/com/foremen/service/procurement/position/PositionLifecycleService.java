package com.foremen.service.procurement.position;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.PurchasePositionService;
import com.foremen.service.model.PurchasePositionServiceExtendedModel;
import com.foremen.service.model.mapper.PurchasePositionServiceMapper;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Decision;

/**
 * FOR-11-00 (R8.6, R8.8): the single write path of {@code PurchasePosition.status}. Every transition
 * is decided by {@link PositionTransitionPolicy}, audited through the position service and announced
 * with a {@link PositionStatusChangedEvent}.
 *
 * <p>A {@link Actor#MANUAL} call (the REST endpoint) is project-scoped like any by-id read; a
 * {@link Actor#SYSTEM} call (internal services of later children, no user context) is not.
 */
@Service
public class PositionLifecycleService {

    static final String NOT_FOUND = "error.purchasePosition.notFound";
    static final String NOT_ALLOWED = "error.purchasePosition.transition.notAllowed";
    static final String GATE_VIOLATION = "error.purchasePosition.transition.gateViolation";

    private final PurchasePositionService positionService;
    private final PurchasePositionServiceMapper mapper;
    private final PositionTransitionPolicy policy;
    private final ApplicationEventPublisher events;

    public PositionLifecycleService(PurchasePositionService positionService,
                                    PurchasePositionServiceMapper mapper,
                                    PositionTransitionPolicy policy,
                                    ApplicationEventPublisher events) {
        this.positionService = positionService;
        this.mapper = mapper;
        this.policy = policy;
        this.events = events;
    }

    @Transactional
    public PurchasePositionServiceExtendedModel transition(Long positionId, PositionStatus target, Actor actor) {
        if (actor == Actor.MANUAL) {
            positionService.assertProjectAccess(positionId);
        }
        PurchasePositionEntity position = positionService.getDao().findById(positionId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND));

        PositionStatus from = position.getStatus();
        Decision decision = policy.decide(position.getSourcingPath(), from, target, actor);
        if (decision == Decision.GATE_VIOLATION) {
            throw new ForemenApiException(HttpStatus.CONFLICT, GATE_VIOLATION);
        }
        if (decision != Decision.ALLOWED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_ALLOWED);
        }

        String before = positionService.serializeEntity(position);
        position.setStatus(target);
        PurchasePositionEntity saved = positionService.getDao().save(position);
        positionService.getEntityManager().flush();
        positionService.saveAuditWithSnapshot(before, saved, "UPDATE");
        events.publishEvent(new PositionStatusChangedEvent(saved.getId(), from, target, actor));
        return mapper.toServiceExtendedModel(saved);
    }

    /** The seeded {@code delivery_statuses.code} that labels the stage (single mapping, R8.8). */
    public String deliveryStatusCode(PositionStatus status) {
        return PositionStage.statusCode(status);
    }
}
