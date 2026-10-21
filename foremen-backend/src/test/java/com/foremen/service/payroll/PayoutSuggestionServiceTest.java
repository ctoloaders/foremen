package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.foremen.dao.WorkReportDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.payroll.dto.PayoutLineDto;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.workercost.WorkerCostService;

/**
 * FOR-06-11 task 8.1 — plain Mockito unit tests for {@link PayoutSuggestionService}, the cost-folding
 * create-dialog preview builder (design §PayoutSuggestionService). The FOR-06-10 cost seam
 * ({@link WorkerCostService}) and the report DAO are mocked; the pure {@link AdvanceCreditCalculator}
 * is composed real so the credit figures are the production ones.
 *
 * <p>Covers the design §Testing Strategy unit cases:
 * <ul>
 *   <li><b>breakdown fidelity</b> — {@code suggest} carries every per-line field of the
 *       {@link PayoutLineDto} (work item, room, period, resolved tier, base-fallback marker, quantity,
 *       unit {@code Worker_Service_Cost}, line {@code Cost_Value}, availability) (R3.3);</li>
 *   <li><b>unavailable line exclusion</b> — a null/zero-rate line is rendered {@code available=false},
 *       its {@code costValue} is {@code null}, and it is excluded from the gross; adding one leaves
 *       the gross unchanged (R3.4).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayoutSuggestionServiceTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long WORKER_ID = 500L;
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 1, 31);

    @Mock
    private WorkReportDao workReportDao;
    @Mock
    private WorkerPayoutDao workerPayoutDao;
    @Mock
    private WorkerCostService workerCostService;

    private final AdvanceCreditCalculator advanceCreditCalculator = new AdvanceCreditCalculator();

    private PayoutSuggestionService service;

    @BeforeEach
    void setUp() {
        service = new PayoutSuggestionService(
                workReportDao, workerPayoutDao, workerCostService, advanceCreditCalculator);
        // Base tier at a 0.40 offer share (the seeded default); no per-worker uplift (base-tier fallback).
        WorkerTypeEntity baseType = new WorkerTypeEntity();
        baseType.setId(1L);
        baseType.setBase(true);
        baseType.setActive(true);
        baseType.setTierPct(new BigDecimal("0.40"));
        baseType.setCode("BASE");
        lenient().when(workerCostService.findBaseType()).thenReturn(baseType);
        lenient().when(workerCostService.activeWorkerTypesByWorkerId(PROJECT_ID))
                .thenReturn(Map.of());
        // No prior payouts -> zero remaining credit, so gross == suggested for a regular preview.
        lenient().when(workerPayoutDao.findByProjectAndWorker(anyLong(), anyLong()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("suggest carries every per-line field of the breakdown (R3.3)")
    void breakdownCarriesEveryPerLineField() {
        // clientUnitPrice 100 -> baseCost round(0.40 × 100 to 0.5) = 40.00; costValue = 40 × qty.
        WorkReportEntity report = report(new BigDecimal("100.00"),
                line(10L, "3.000"));
        when(workReportDao.findApprovedInPeriodWithRooms(PROJECT_ID, WORKER_ID, FROM, TO))
                .thenReturn(List.of(report));

        PayoutSuggestionDto dto = service.suggest(PROJECT_ID, WORKER_ID, FROM, TO, false);

        assertThat(dto.isAdvance()).isFalse();
        assertThat(dto.breakdown()).hasSize(1);
        PayoutLineDto l = dto.breakdown().get(0);
        assertThat(l.workReportRoomId()).isEqualTo(10L);
        assertThat(l.workItemName()).isEqualTo("Work item PL");
        assertThat(l.roomName()).isEqualTo("Room label");
        assertThat(l.periodFrom()).isEqualTo(report.getPerformedFrom());
        assertThat(l.periodTo()).isEqualTo(report.getPerformedTo());
        assertThat(l.workerTypeName()).isEqualTo("BASE");       // base-tier fallback name
        assertThat(l.baseTierFallback()).isTrue();              // no per-worker type -> fallback (R2.4)
        assertThat(l.quantity()).isEqualByComparingTo("3.000");
        assertThat(l.unitServiceCost()).isEqualByComparingTo("40.00");
        assertThat(l.costValue()).isEqualByComparingTo("120.00"); // 40.00 × 3
        assertThat(l.available()).isTrue();
        // gross equals the single available line's cost; with no credit, suggested == gross (R3.2).
        assertThat(dto.gross()).isEqualByComparingTo("120.00");
        assertThat(dto.appliedCredit()).isEqualByComparingTo("0");
        assertThat(dto.suggested()).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("an unavailable (zero-rate) line is marked excluded and contributes nothing to gross (R3.4)")
    void unavailableLineRenderedExcluded() {
        // One available line (rate 100 -> cost 40 × 2 = 80) + one unavailable line (rate 0 -> null cost).
        WorkReportEntity available = report(new BigDecimal("100.00"), line(10L, "2.000"));
        WorkReportEntity unavailable = report(BigDecimal.ZERO, line(11L, "5.000"));
        when(workReportDao.findApprovedInPeriodWithRooms(PROJECT_ID, WORKER_ID, FROM, TO))
                .thenReturn(List.of(available, unavailable));

        PayoutSuggestionDto dto = service.suggest(PROJECT_ID, WORKER_ID, FROM, TO, false);

        assertThat(dto.breakdown()).hasSize(2);
        PayoutLineDto bad = dto.breakdown().stream()
                .filter(l -> l.workReportRoomId().equals(11L)).findFirst().orElseThrow();
        assertThat(bad.available()).isFalse();        // marked unavailable (R3.4)
        assertThat(bad.costValue()).isNull();          // never a fabricated zero
        assertThat(bad.unitServiceCost()).isNull();
        // gross is ONLY the available line's cost — the unavailable line adds nothing (R3.4).
        assertThat(dto.gross()).isEqualByComparingTo("80.00"); // 40 × 2
    }

    @Test
    @DisplayName("adding an unavailable line leaves the gross unchanged (R3.4)")
    void addingUnavailableLineDoesNotChangeGross() {
        WorkReportEntity available = report(new BigDecimal("100.00"), line(10L, "2.000"));
        when(workReportDao.findApprovedInPeriodWithRooms(PROJECT_ID, WORKER_ID, FROM, TO))
                .thenReturn(List.of(available));
        BigDecimal grossBefore = service.suggest(PROJECT_ID, WORKER_ID, FROM, TO, false).gross();

        // add a zero-rate (unavailable) report; gross must be identical.
        WorkReportEntity unavailable = report(BigDecimal.ZERO, line(11L, "9.000"));
        when(workReportDao.findApprovedInPeriodWithRooms(PROJECT_ID, WORKER_ID, FROM, TO))
                .thenReturn(List.of(available, unavailable));
        BigDecimal grossAfter = service.suggest(PROJECT_ID, WORKER_ID, FROM, TO, false).gross();

        assertThat(grossAfter)
                .as("adding an unavailable line leaves the gross unchanged (R3.4)")
                .isEqualByComparingTo(grossBefore);
    }

    // =======================================================================
    // fixtures
    // =======================================================================

    private static WorkReportEntity report(BigDecimal clientUnitPrice, WorkReportRoomEntity... lines) {
        WorkReportEntity r = new WorkReportEntity();
        r.setClientUnitPrice(clientUnitPrice);
        r.setPerformedFrom(FROM);
        r.setPerformedTo(TO);

        UserEntity worker = new UserEntity();
        worker.setId(WORKER_ID);
        r.setWorker(worker);

        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setNamePL("Work item PL");
        workItem.setCode("WI");
        r.setWorkItem(workItem);

        List<WorkReportRoomEntity> rooms = new ArrayList<>();
        for (WorkReportRoomEntity l : lines) {
            l.setReport(r);
            rooms.add(l);
        }
        r.setRooms(rooms);
        return r;
    }

    private static WorkReportRoomEntity line(Long id, String quantity) {
        WorkReportRoomEntity l = new WorkReportRoomEntity();
        l.setId(id);
        l.setQuantity(new BigDecimal(quantity));
        l.setPlanQuantity(new BigDecimal(quantity));
        RoomEntity room = new RoomEntity();
        room.setLabel("Room label");
        l.setRoom(room);
        return l;
    }
}
