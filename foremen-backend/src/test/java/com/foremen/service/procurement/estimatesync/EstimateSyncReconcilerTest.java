package com.foremen.service.procurement.estimatesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.service.procurement.estimatesync.EstimateSyncReconciler.Outcome;
import com.foremen.service.procurement.estimatesync.EstimateSyncReconciler.Target;

/** FOR-11-06 (R5.5): re-sync rules per position stage. Validates: Requirements 5.5 */
class EstimateSyncReconcilerTest {

    private static MeasurementUnitEntity unit(long id) {
        MeasurementUnitEntity u = new MeasurementUnitEntity();
        u.setId(id);
        return u;
    }

    private static PurchasePositionEntity position(PositionStatus status, String qty) {
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setStatus(status);
        p.setQuantity(new BigDecimal(qty));
        p.setUnit(unit(1));
        return p;
    }

    private static Target target(String qty, Long materialId) {
        return new Target(new BigDecimal(qty), unit(1), materialId, null, null, null);
    }

    @Test
    void plannedPositionTakesNewQuantityAndNeedByDate() {
        PurchasePositionEntity p = position(PositionStatus.PLANNED, "10");
        Target t = new Target(new BigDecimal("12"), unit(1), 5L, new BigDecimal("9.50"), null,
                LocalDate.of(2030, 1, 2));

        assertEquals(Outcome.UPDATED, EstimateSyncReconciler.reconcile(p, t));
        assertEquals(0, p.getQuantity().compareTo(new BigDecimal("12")));
        assertEquals(LocalDate.of(2030, 1, 2), p.getNeedByDate());
        assertEquals(0, p.getSalePrice().compareTo(new BigDecimal("9.50")));
    }

    @Test
    void secondRunWithSameValuesIsUnchanged() {
        PurchasePositionEntity p = position(PositionStatus.PLANNED, "10.000");

        assertEquals(Outcome.UNCHANGED, EstimateSyncReconciler.reconcile(p, target("10", null)));
    }

    @Test
    void proposedPositionKeepsQuantityAndGetsChangedFlag() {
        PurchasePositionEntity p = position(PositionStatus.PROPOSED, "10");

        assertEquals(Outcome.FLAGGED, EstimateSyncReconciler.reconcile(p, target("15", null)));
        assertEquals(0, p.getQuantity().compareTo(new BigDecimal("10")));
        assertTrue(p.isEstimateChanged());
    }

    @Test
    void proposedPositionWithDifferentMaterialIsFlaggedAndFlagClearsWhenEqualAgain() {
        PurchasePositionEntity p = position(PositionStatus.PROPOSED, "10");
        FinishingMaterialEntity m = new FinishingMaterialEntity();
        m.setId(1L);
        p.setPlannedMaterial(m);

        assertEquals(Outcome.FLAGGED, EstimateSyncReconciler.reconcile(p, target("10", 2L)));
        assertEquals(Outcome.UNCHANGED, EstimateSyncReconciler.reconcile(p, target("10", 1L)));
        assertFalse(p.isEstimateChanged());
    }

    @Test
    void reappearingRowClearsRemovedFlagAndCancelledIsUntouched() {
        PurchasePositionEntity p = position(PositionStatus.PROPOSED, "10");
        p.setEstimateRemoved(true);
        EstimateSyncReconciler.reconcile(p, target("10", null));
        assertFalse(p.isEstimateRemoved());

        PurchasePositionEntity c = position(PositionStatus.CANCELLED, "10");
        assertEquals(Outcome.UNCHANGED, EstimateSyncReconciler.reconcile(c, target("99", null)));
        assertEquals(0, c.getQuantity().compareTo(new BigDecimal("10")));
    }
}
