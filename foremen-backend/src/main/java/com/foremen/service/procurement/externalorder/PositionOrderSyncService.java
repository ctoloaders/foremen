package com.foremen.service.procurement.externalorder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.service.PurchasePositionService;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;

/**
 * FOR-11-03 (R13.2, R13.3, R17): derives the SYSTEM-owned procurement fields of a position from its
 * lines in committed external orders ({@code SENT_FOR_PAYMENT}, {@code PAID}) and moves the stage
 * ({@code ORDERED}, {@code SUPPLIER_PAID} and the reversals) one step at a time through
 * {@link PositionLifecycleService}.
 *
 * <p>Writes only {@code orderedQuantity}, {@code actualPurchasePrice}, {@code deliveryCostShare} and
 * {@code status}, directly on the entity (not through the REST update path). Idempotent: a second call
 * with unchanged data writes and transitions nothing. Positions in any status outside
 * {@code WITH_BUYER / ORDERED / SUPPLIER_PAID} ({@code IN_DELIVERY} and later, or earlier stages) are
 * left untouched entirely.
 */
@Service
public class PositionOrderSyncService {

    /** The stage chain has at most a few legal steps; the bound only guards against a resolver loop. */
    private static final int MAX_STEPS = 4;

    private final ExternalOrderLineDao lineDao;
    private final PurchasePositionService positionService;
    private final PositionLifecycleService lifecycle;

    public PositionOrderSyncService(ExternalOrderLineDao lineDao,
                                    PurchasePositionService positionService,
                                    PositionLifecycleService lifecycle) {
        this.lineDao = lineDao;
        this.positionService = positionService;
        this.lifecycle = lifecycle;
    }

    @Transactional
    public void syncAll(Collection<Long> positionIds) {
        if (positionIds == null) {
            return;
        }
        for (Long id : new LinkedHashSet<>(positionIds)) {
            if (id != null) {
                sync(id);
            }
        }
    }

    @Transactional
    public void sync(Long positionId) {
        PurchasePositionEntity position = positionService.getDao().findById(positionId).orElse(null);
        if (position == null || !isSynced(position.getStatus())) {
            return;
        }

        List<ExternalOrderLineEntity> lines = lineDao.findCommittedByPositionId(positionId);
        writeDerivedFields(position, lines);

        BigDecimal coverage = lines.stream().map(ExternalOrderLineEntity::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ExternalOrderStatus> orderStatuses = lines.stream()
                .map(l -> l.getExternalOrder().getStatus()).toList();

        for (int i = 0; i < MAX_STEPS; i++) {
            PositionStatus current = position.getStatus();
            PositionStatus next = PositionOrderStageResolver.resolve(
                    current, position.getQuantity(), coverage, orderStatuses);
            if (next == current) {
                return;
            }
            lifecycle.transition(positionId, next, Actor.SYSTEM);
            // The lifecycle service works on the same managed entity inside this transaction;
            // re-read defensively in case a different instance was returned.
            position = positionService.getDao().findById(positionId).orElse(position);
        }
    }

    private static boolean isSynced(PositionStatus status) {
        return status == PositionStatus.WITH_BUYER || status == PositionStatus.ORDERED
                || status == PositionStatus.SUPPLIER_PAID;
    }

    private void writeDerivedFields(PurchasePositionEntity position, List<ExternalOrderLineEntity> lines) {
        BigDecimal ordered = BigDecimal.ZERO;
        BigDecimal share = BigDecimal.ZERO;
        BigDecimal weighted = BigDecimal.ZERO;
        for (ExternalOrderLineEntity l : lines) {
            ordered = ordered.add(l.getQuantity());
            share = share.add(l.getDeliveryCostShare() == null ? BigDecimal.ZERO : l.getDeliveryCostShare());
            weighted = weighted.add(l.getUnitPurchaseNet().multiply(l.getQuantity()));
        }
        BigDecimal newOrdered = ordered.setScale(3, RoundingMode.HALF_UP);
        BigDecimal newShare = share.setScale(2, RoundingMode.HALF_UP);
        BigDecimal newPrice = ordered.signum() > 0
                ? weighted.divide(ordered, 2, RoundingMode.HALF_UP)
                : null;

        boolean changed = differs(position.getOrderedQuantity(), newOrdered)
                || differs(position.getDeliveryCostShare(), newShare)
                || differs(position.getActualPurchasePrice(), newPrice);
        if (!changed) {
            return;
        }
        String before = positionService.serializeEntity(position);
        position.setOrderedQuantity(newOrdered);
        position.setDeliveryCostShare(newShare);
        position.setActualPurchasePrice(newPrice);
        PurchasePositionEntity saved = positionService.getDao().save(position);
        positionService.getEntityManager().flush();
        positionService.saveAuditWithSnapshot(before, saved, "UPDATE");
    }

    private static boolean differs(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a != b;
        }
        return a.compareTo(b) != 0;
    }
}
