package com.foremen.service.procurement.estimatesync;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;

/**
 * Pure re-sync rules for one existing estimate-derived position (FOR-11-06 R5.5, design 6.2). No I/O.
 */
final class EstimateSyncReconciler {

    enum Outcome { UPDATED, UNCHANGED, FLAGGED }

    /** The values an estimate row resolves to for an existing position. */
    record Target(BigDecimal quantity, MeasurementUnitEntity unit, Long materialId, BigDecimal salePrice,
                  BigDecimal plannedPurchasePrice, LocalDate needByDate) {
    }

    private EstimateSyncReconciler() {
    }

    /**
     * Applies the rules to {@code position}: PLANNED gets quantity, unit, prices and need-by date refreshed
     * (UPDATED when anything changed); CANCELLED is untouched; later stages keep quantity, material and
     * prices and only get {@code estimate_changed} set or cleared (FLAGGED when it ends up set).
     * A row that is present again clears {@code estimate_removed}.
     */
    static Outcome reconcile(PurchasePositionEntity position, Target target) {
        PositionStatus status = position.getStatus();
        if (status == PositionStatus.CANCELLED) {
            return Outcome.UNCHANGED;
        }
        boolean changed = position.isEstimateRemoved();
        position.setEstimateRemoved(false);
        if (status == PositionStatus.PLANNED) {
            changed |= position.isEstimateChanged();
            position.setEstimateChanged(false);
            if (differs(position.getQuantity(), target.quantity())) {
                position.setQuantity(target.quantity());
                changed = true;
            }
            if (target.unit() != null && (position.getUnit() == null
                    || !Objects.equals(position.getUnit().getId(), target.unit().getId()))) {
                position.setUnit(target.unit());
                changed = true;
            }
            if (target.salePrice() != null && differs(position.getSalePrice(), target.salePrice())) {
                position.setSalePrice(target.salePrice());
                changed = true;
            }
            if (target.plannedPurchasePrice() != null
                    && differs(position.getPlannedPurchasePrice(), target.plannedPurchasePrice())) {
                position.setPlannedPurchasePrice(target.plannedPurchasePrice());
                changed = true;
            }
            if (target.needByDate() != null && !target.needByDate().equals(position.getNeedByDate())) {
                position.setNeedByDate(target.needByDate());
                changed = true;
            }
            return changed ? Outcome.UPDATED : Outcome.UNCHANGED;
        }
        boolean differs = differs(position.getQuantity(), target.quantity())
                || (target.materialId() != null && position.getPlannedMaterial() != null
                && !Objects.equals(position.getPlannedMaterial().getId(), target.materialId()));
        position.setEstimateChanged(differs);
        return differs ? Outcome.FLAGGED : Outcome.UNCHANGED;
    }

    private static boolean differs(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a != b;
        }
        return a.compareTo(b) != 0;
    }
}
