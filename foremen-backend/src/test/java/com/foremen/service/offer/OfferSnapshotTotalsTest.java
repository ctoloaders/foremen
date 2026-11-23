package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.OfferDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.DiscountScope;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferDiscountEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.service.OfferService;
import com.foremen.service.audit.AuditLogDao;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FOR-05-07b D7 (2.15): the offer totals come from the snapshot, not from the live estimate.
 */
class OfferSnapshotTotalsTest {

    private final OfferDao offerDao = mock(OfferDao.class);
    private final OfferService service = new OfferService(
            offerDao, mock(EstimateDao.class), mock(EstimateLineRoomMaterialDao.class),
            mock(OfferPackageDao.class), mock(UserDao.class),
            mock(com.foremen.service.model.mapper.OfferServiceMapper.class),
            mock(com.foremen.service.ProjectAccessCache.class), mock(AuditLogDao.class),
            mock(EntityManager.class), new OfferStatusMachine(), new OfferTotalsCalculator(),
            new DiscountResolver(), mock(com.foremen.service.estimate.DraftGateGuard.class),
            mock(com.foremen.service.estimate.EstimateAssignmentService.class),
            mock(ApplicationEventPublisher.class), mock(ClientOfferReadModelAssembler.class));

    @Test
    @DisplayName("card totals equal the snapshot after an estimate edit (recompute and read)")
    void totalsIgnoreEstimateEdit() {
        EstimateEntity estimate = estimate(line(1L, 10L, "100.00", "2"), line(2L, 11L, "50.00", "1"));
        OfferEntity offer = offer(estimate);
        offer.setSnapshot(OfferSnapshotBuilder.build(estimate, new BigDecimal("23"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        // The estimate is edited after the snapshot was taken: price and quantity change, a line appears.
        estimate.getLines().get(0).setUnitPrice(new BigDecimal("999.00"));
        estimate.getLines().add(line(3L, 12L, "70.00", "4"));

        service.recomputeTotals(offer);
        assertThat(offer.getTotalNet()).isEqualByComparingTo("250.00");
        assertThat(offer.getTotalVat()).isEqualByComparingTo("57.50");
        assertThat(offer.getTotalGross()).isEqualByComparingTo("307.50");

        // The read path returns the stored totals, never a live recompute.
        offer.setTotalNet(new BigDecimal("250.00"));
        when(offerDao.findById(700L)).thenReturn(Optional.of(offer));
        assertThat(service.getOffer(700L).getTotalNet()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("discounts apply on snapshot prices: GLOBAL percent, CATEGORY, LINE by estimate line id")
    void discountsOnSnapshotPrices() {
        EstimateEntity estimate = estimate(line(1L, 10L, "100.00", "2"), line(2L, 11L, "50.00", "1"));
        OfferEntity offer = offer(estimate);
        offer.setSnapshot(OfferSnapshotBuilder.build(estimate, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        estimate.getLines().get(0).setUnitPrice(new BigDecimal("1.00")); // live edit must not matter

        offer.getDiscounts().add(discount(DiscountScope.LINE, 1L, DiscountKind.PERCENT, "10"));
        service.recomputeTotals(offer);
        assertThat(offer.getTotalNet()).isEqualByComparingTo("230.00"); // 200 - 20 + 50

        offer.getDiscounts().clear();
        offer.getDiscounts().add(discount(DiscountScope.GLOBAL, null, DiscountKind.PERCENT, "10"));
        service.recomputeTotals(offer);
        assertThat(offer.getTotalNet()).isEqualByComparingTo("225.00");

        offer.getDiscounts().clear();
        offer.getDiscounts().add(discount(DiscountScope.CATEGORY, 5L, DiscountKind.ABSOLUTE, "30"));
        service.recomputeTotals(offer);
        assertThat(offer.getTotalNet()).isEqualByComparingTo("190.00"); // 30 off each of the two lines
    }

    @Test
    @DisplayName("legacy offer without a snapshot keeps the live path")
    void legacyKeepsLivePath() {
        EstimateEntity estimate = estimate(line(1L, 10L, "100.00", "2"));
        OfferEntity offer = offer(estimate);
        service.recomputeTotals(offer);
        assertThat(offer.getTotalNet()).isEqualByComparingTo("200.00");
        estimate.getLines().get(0).setUnitPrice(new BigDecimal("300.00"));
        service.recomputeTotals(offer);
        assertThat(offer.getTotalNet()).isEqualByComparingTo("600.00");
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private static OfferDiscountEntity discount(DiscountScope scope, Long target, DiscountKind kind, String value) {
        OfferDiscountEntity d = new OfferDiscountEntity();
        d.setScope(scope);
        d.setTargetId(target);
        d.setKind(kind);
        d.setValue(new BigDecimal(value));
        return d;
    }

    private static OfferEntity offer(EstimateEntity estimate) {
        OfferEntity offer = new OfferEntity();
        offer.setId(700L);
        offer.setStatus(OfferStatus.DRAFT);
        offer.setEstimate(estimate);
        offer.setDiscounts(new ArrayList<>());
        return offer;
    }

    private static EstimateEntity estimate(EstimateLineEntity... lines) {
        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(9L);
        estimate.setLines(new ArrayList<>(List.of(lines)));
        return estimate;
    }

    private static EstimateLineEntity line(Long id, Long workId, String price, String qty) {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setId(5L);
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode("M2");
        WorkItemEntity work = new WorkItemEntity();
        work.setId(workId);
        work.setWorkCategory(category);
        work.setUnit(unit);
        RoomEntity room = new RoomEntity();
        room.setId(1L);
        room.setLabel("A");
        EstimateLineRoomQtyEntity cell = new EstimateLineRoomQtyEntity();
        cell.setRoom(room);
        cell.setQuantity(new BigDecimal(qty));
        EstimateLineEntity l = new EstimateLineEntity();
        l.setId(id);
        l.setWorkItem(work);
        l.setUnitPrice(new BigDecimal(price));
        l.setQuantity(new BigDecimal(qty));
        l.setRoomQtys(new ArrayList<>(List.of(cell)));
        return l;
    }
}
