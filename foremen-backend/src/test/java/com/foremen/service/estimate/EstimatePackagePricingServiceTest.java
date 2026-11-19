package com.foremen.service.estimate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.foremen.dao.RoomDao;
import com.foremen.dao.WorkPackageOverrideDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPackageOverrideEntity;

/**
 * FOR-05-04 additional tasks (tasks 2 and 4): {@link EstimatePackagePricingService} prices an
 * estimate as package price per m2 x room floor area + works not covered by the package, and feeds
 * the same figure to the offer totals.
 */
@Tag("Feature: FOR-05-04-additional-tasks, package pricing service")
class EstimatePackagePricingServiceTest {

    private static final Long PROJECT_ID = 5L;
    private static final Long PACKAGE_ID = 9L;

    private final RoomDao roomDao = mock(RoomDao.class);
    private final WorkPackageOverrideDao overrideDao = mock(WorkPackageOverrideDao.class);
    private final EstimatePackagePricingService service = new EstimatePackagePricingService(roomDao, overrideDao);

    @Test
    @DisplayName("no applied package: empty (price-list calculation only)")
    void noPackageIsEmpty() {
        EstimateEntity estimate = estimate(false);

        assertThat(service.pricingOf(estimate)).isEmpty();
        assertThat(service.clientPricesOf(estimate)).isEmpty();
    }

    @Test
    @DisplayName("applied package: zlM2 x summed room area + net of works beyond the package")
    void packageTotalPlusExtras() {
        wireRoomsAndMembership();
        EstimateEntity estimate = estimate(true);
        estimate.getLines().add(line(100L, 1L, "500.00", "2"));   // member of the package -> covered
        estimate.getLines().add(line(101L, 2L, "40.00", "3"));    // not a member -> extra 120.00

        EstimatePackagePricing.Result r = service.pricingOf(estimate).orElseThrow();

        assertThat(r.areaM2()).isEqualByComparingTo("50.00");              // 20.00 + 30.00
        assertThat(r.packageSubtotal()).isEqualByComparingTo("60000.00");  // 1200 x 50
        assertThat(r.extrasSubtotal()).isEqualByComparingTo("120.00");
        assertThat(r.total()).isEqualByComparingTo("60120.00");
    }

    @Test
    @DisplayName("offer client prices sum to the same total: extras by line id + package subtotal entry")
    void offerPricesMatchEstimateTotal() {
        wireRoomsAndMembership();
        EstimateEntity estimate = estimate(true);
        estimate.getLines().add(line(100L, 1L, "500.00", "2"));
        estimate.getLines().add(line(101L, 2L, "40.00", "3"));

        Map<Long, BigDecimal> prices = service.clientPricesOf(estimate).orElseThrow();

        assertThat(prices).doesNotContainKey(100L);
        assertThat(prices.get(101L)).isEqualByComparingTo("120.00");
        assertThat(prices.get(EstimatePackagePricingService.PACKAGE_PRICE_KEY)).isEqualByComparingTo("60000.00");
        assertThat(prices.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(service.pricingOf(estimate).orElseThrow().total());
    }

    private void wireRoomsAndMembership() {
        RoomEntity r1 = new RoomEntity();
        r1.setFloorArea(new BigDecimal("20.00"));
        RoomEntity r2 = new RoomEntity();
        r2.setFloorArea(new BigDecimal("30.00"));
        when(roomDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(r1, r2));
        WorkPackageOverrideEntity member = new WorkPackageOverrideEntity();
        member.setMember(true);
        member.setWorkItem(work(1L));
        member.setOfferPackage(offerPackage());
        WorkPackageOverrideEntity notMember = new WorkPackageOverrideEntity();
        notMember.setMember(false);
        notMember.setWorkItem(work(2L));
        notMember.setOfferPackage(offerPackage());
        when(overrideDao.findAllWithWorkItemAndPackage()).thenReturn(List.of(member, notMember));
    }

    private EstimateEntity estimate(boolean packageApplied) {
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        project.setPackageApplied(packageApplied);
        project.setOfferPackage(packageApplied ? offerPackage() : null);
        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(1L);
        estimate.setProject(project);
        return estimate;
    }

    private static OfferPackageEntity offerPackage() {
        OfferPackageEntity p = new OfferPackageEntity();
        p.setId(PACKAGE_ID);
        p.setCode("STANDARD");
        p.setZlM2(new BigDecimal("1200.00"));
        return p;
    }

    private static WorkItemEntity work(Long id) {
        WorkItemEntity w = new WorkItemEntity();
        w.setId(id);
        return w;
    }

    private static EstimateLineEntity line(Long id, Long workId, String unitPrice, String qty) {
        EstimateLineEntity line = new EstimateLineEntity();
        line.setId(id);
        line.setWorkItem(work(workId));
        line.setUnitPrice(new BigDecimal(unitPrice));
        line.setQuantity(new BigDecimal(qty));
        return line;
    }
}
