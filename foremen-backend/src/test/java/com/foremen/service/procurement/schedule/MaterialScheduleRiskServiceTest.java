package com.foremen.service.procurement.schedule;

import com.foremen.dao.ProjectScheduleBarDao;
import com.foremen.dao.ProjectScheduleBarDao.EarliestBarRow;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.schedule.MaterialScheduleRiskService.Flags;
import com.foremen.service.procurement.schedule.MaterialScheduleRiskService.ProjectCategory;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaterialScheduleRiskServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final LocalDate ANCHOR = LocalDate.of(2026, 3, 2);

    private final ProjectScheduleBarDao dao = mock(ProjectScheduleBarDao.class);
    private final MaterialScheduleRiskService service = new MaterialScheduleRiskService(dao);

    private static PurchasePositionEntity position(PositionStatus status, SourcingPath path,
                                                   LocalDate orderBy, LocalDate arrival) {
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setStatus(status);
        p.setSourcingPath(path);
        p.setOrderByDate(orderBy);
        p.setPlannedDeliveryDate(arrival);
        return p;
    }

    @Test
    void orderByMissedBoundaryAndStages() {
        assertThat(MaterialScheduleRiskService.orderByMissed(
                PositionStatus.WITH_BUYER, SourcingPath.AGREED, TODAY, TODAY)).isTrue();
        assertThat(MaterialScheduleRiskService.orderByMissed(
                PositionStatus.PLANNED, SourcingPath.AGREED, TODAY.minusDays(1), TODAY)).isTrue();
        assertThat(MaterialScheduleRiskService.orderByMissed(
                PositionStatus.WITH_BUYER, SourcingPath.AGREED, TODAY.plusDays(1), TODAY)).isFalse();
        assertThat(MaterialScheduleRiskService.orderByMissed(
                PositionStatus.ORDERED, SourcingPath.AGREED, TODAY.minusDays(1), TODAY)).isFalse();
        assertThat(MaterialScheduleRiskService.orderByMissed(
                PositionStatus.CANCELLED, SourcingPath.AGREED, TODAY.minusDays(1), TODAY)).isFalse();
        assertThat(MaterialScheduleRiskService.orderByMissed(
                PositionStatus.PLANNED, SourcingPath.CLIENT_OWN, TODAY.minusDays(1), TODAY)).isFalse();
        assertThat(MaterialScheduleRiskService.orderByMissed(
                PositionStatus.PLANNED, SourcingPath.AGREED, null, TODAY)).isFalse();
    }

    @Test
    void downtimeRiskOnlyWhenArrivalStrictlyAfterWorksStart() {
        LocalDate start = ANCHOR.plusDays(4);
        assertThat(MaterialScheduleRiskService.downtimeRisk(
                PositionStatus.IN_DELIVERY, start.plusDays(1), start)).isTrue();
        assertThat(MaterialScheduleRiskService.downtimeRisk(
                PositionStatus.IN_DELIVERY, start, start)).isFalse();
        assertThat(MaterialScheduleRiskService.downtimeRisk(
                PositionStatus.RECEIVED, start.plusDays(1), start)).isFalse();
        assertThat(MaterialScheduleRiskService.downtimeRisk(
                PositionStatus.IN_DELIVERY, null, start)).isFalse();
        assertThat(MaterialScheduleRiskService.downtimeRisk(
                PositionStatus.IN_DELIVERY, start.plusDays(1), null)).isFalse();
    }

    @Test
    void flagsCombinesAllThree() {
        Flags f = MaterialScheduleRiskService.flags(
                position(PositionStatus.WITH_BUYER, SourcingPath.AGREED, TODAY.minusDays(1), null),
                ANCHOR, TODAY);
        assertThat(f).isEqualTo(new Flags(true, false, true));

        Flags g = MaterialScheduleRiskService.flags(
                position(PositionStatus.IN_DELIVERY, SourcingPath.AGREED, TODAY.minusDays(5), ANCHOR.plusDays(3)),
                ANCHOR, TODAY);
        assertThat(g).isEqualTo(new Flags(false, true, false));

        Flags h = MaterialScheduleRiskService.flags(
                position(PositionStatus.RECEIVED, SourcingPath.AGREED, null, null), null, TODAY);
        assertThat(h).isEqualTo(new Flags(false, false, false));
    }

    @Test
    void worksStartsUsesEarliestBarHalfDayConversionAndKeepsRequestedPairs() {
        when(dao.findEarliestBarStarts(any(), any())).thenReturn(List.of(
                new EarliestBarRow(1L, 10L, ANCHOR, 5),   // day 3 -> anchor + 2
                new EarliestBarRow(1L, 11L, ANCHOR, 1),   // not requested
                new EarliestBarRow(2L, 10L, ANCHOR, 2))); // day 1 -> anchor

        Map<ProjectCategory, LocalDate> result = service.worksStarts(
                List.of(new ProjectCategory(1L, 10L), new ProjectCategory(2L, 10L)));

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(
                new ProjectCategory(1L, 10L), ANCHOR.plusDays(2),
                new ProjectCategory(2L, 10L), ANCHOR));
        verify(dao, times(1)).findEarliestBarStarts(any(), any());
    }

    @Test
    void worksStartsMissingAnchorOrBarHasNoEntry() {
        when(dao.findEarliestBarStarts(any(), any())).thenReturn(List.of(
                new EarliestBarRow(1L, 10L, null, 3),
                new EarliestBarRow(1L, 11L, ANCHOR, null)));

        assertThat(service.worksStarts(
                List.of(new ProjectCategory(1L, 10L), new ProjectCategory(1L, 11L),
                        new ProjectCategory(1L, 12L)))).isEmpty();
    }

    @Test
    void worksStartsEmptyInputSkipsQuery() {
        assertThat(service.worksStarts(List.of())).isEmpty();
        verify(dao, never()).findEarliestBarStarts(any(), any());
    }
}
