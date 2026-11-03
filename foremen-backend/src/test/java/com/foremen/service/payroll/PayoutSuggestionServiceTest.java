package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.foremen.dao.WorkReportRoomDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.payroll.dto.PayoutLineDto;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.workercost.WorkerCostService;
import com.foremen.service.workercost.WorkerProfile;

/**
 * FOR-07-06 task 5.1 — Mockito unit tests for the worker-wide {@link PayoutSuggestionService}
 * (design §PayoutSuggestionService). The cost seam and DAOs are mocked; the pure
 * {@link AdvanceCreditCalculator} is real.
 *
 * <p>Base tier 0.40, rate 100 ⇒ unit cost 40.00 per unit of quantity.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayoutSuggestionServiceTest {

    private static final Long WORKER_ID = 500L;
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 1, 31);
    private static final BigDecimal RATE = new BigDecimal("100.00");

    @Mock
    private WorkReportDao workReportDao;
    @Mock
    private WorkReportRoomDao workReportRoomDao;
    @Mock
    private WorkerPayoutDao workerPayoutDao;
    @Mock
    private WorkerCostService workerCostService;

    private PayoutSuggestionService service;
    private WorkerTypeEntity baseType;

    @BeforeEach
    void setUp() {
        service = new PayoutSuggestionService(workReportDao, workReportRoomDao, workerPayoutDao,
                workerCostService, new AdvanceCreditCalculator());
        baseType = new WorkerTypeEntity();
        baseType.setId(1L);
        baseType.setBase(true);
        baseType.setActive(true);
        baseType.setTierPct(new BigDecimal("0.40"));
        baseType.setCode("BASE");
        lenient().when(workerCostService.findBaseType()).thenReturn(baseType);
        lenient().when(workerCostService.workerProfilesById(any())).thenReturn(Map.of());
        lenient().when(workerPayoutDao.findByWorker(anyLong())).thenReturn(List.of());
    }

    @Test
    @DisplayName("breakdown carries project, paid, remainder and allocation preview (R4.6)")
    void breakdownCarriesEveryPerLineField() {
        WorkReportRoomEntity l = line(10L, "3.000", "20.00", report(RATE, 7L, "Alpha", FROM, TO));
        when(workReportRoomDao.findPayableLines(WORKER_ID, TO)).thenReturn(List.of(l));

        PayoutSuggestionDto dto = service.suggest(WORKER_ID, FROM, TO, false);

        assertThat(dto.isAdvance()).isFalse();
        assertThat(dto.breakdown()).hasSize(1);
        PayoutLineDto b = dto.breakdown().get(0);
        assertThat(b.workReportRoomId()).isEqualTo(10L);
        assertThat(b.projectId()).isEqualTo(7L);
        assertThat(b.projectName()).isEqualTo("Alpha");
        assertThat(b.workItemName()).isEqualTo("Work item PL");
        assertThat(b.roomName()).isEqualTo("Room label");
        assertThat(b.periodFrom()).isEqualTo(FROM);
        assertThat(b.periodTo()).isEqualTo(TO);
        assertThat(b.workerTypeName()).isEqualTo("BASE");
        assertThat(b.baseTierFallback()).isTrue();
        assertThat(b.seniorityPct()).isEqualByComparingTo("0");
        assertThat(b.quantity()).isEqualByComparingTo("3.000");
        assertThat(b.unitServiceCost()).isEqualByComparingTo("40.00");
        assertThat(b.costValue()).isEqualByComparingTo("120.00");
        assertThat(b.paidAmount()).isEqualByComparingTo("20.00");
        assertThat(b.remainder()).isEqualByComparingTo("100.00");
        assertThat(b.allocationPreview()).isEqualByComparingTo("100.00");
        assertThat(b.available()).isTrue();
        // gross = Σ remainders, not Σ costs (R4.4).
        assertThat(dto.gross()).isEqualByComparingTo("100.00");
        assertThat(dto.appliedCredit()).isEqualByComparingTo("0");
        assertThat(dto.suggested()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("lines across projects; fully paid lines dropped; order preserved (R4.2, R4.3)")
    void crossProjectCandidatesAndFullyPaidDropped() {
        LocalDate older = LocalDate.of(2025, 11, 3); // before periodFrom: still a candidate (R4.2)
        WorkReportRoomEntity a = line(1L, "1.000", "0", report(RATE, 7L, "Alpha", older, older));
        WorkReportRoomEntity paid = line(2L, "1.000", "40.00", report(RATE, 7L, "Alpha", older, older));
        WorkReportRoomEntity b = line(3L, "2.000", "0", report(RATE, 8L, "Beta", FROM, TO));
        when(workReportRoomDao.findPayableLines(WORKER_ID, TO)).thenReturn(List.of(a, paid, b));

        PayoutSuggestionDto dto = service.suggest(WORKER_ID, FROM, TO, false);

        assertThat(dto.breakdown()).extracting(PayoutLineDto::workReportRoomId).containsExactly(1L, 3L);
        assertThat(dto.breakdown()).extracting(PayoutLineDto::projectName).containsExactly("Alpha", "Beta");
        assertThat(dto.gross()).isEqualByComparingTo("120.00"); // 40 + 80
    }

    @Test
    @DisplayName("unavailable line is listed, excluded from gross and allocated nothing (R4.4)")
    void unavailableLineExcluded() {
        WorkReportRoomEntity ok = line(10L, "2.000", "0", report(RATE, 7L, "Alpha", FROM, TO));
        WorkReportRoomEntity bad = line(11L, "5.000", "0", report(BigDecimal.ZERO, 7L, "Alpha", FROM, TO));
        when(workReportRoomDao.findPayableLines(WORKER_ID, TO)).thenReturn(List.of(bad, ok));

        PayoutSuggestionDto dto = service.suggest(WORKER_ID, FROM, TO, false);

        assertThat(dto.breakdown()).hasSize(2);
        PayoutLineDto u = dto.breakdown().get(0);
        assertThat(u.available()).isFalse();
        assertThat(u.costValue()).isNull();
        assertThat(u.unitServiceCost()).isNull();
        assertThat(u.remainder()).isNull();
        assertThat(u.allocationPreview()).isEqualByComparingTo("0");
        assertThat(dto.gross()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("worker-wide credit: applied = min(credit, gross), preview distributes oldest-first (R4.5, R7.1)")
    void workerWideCreditAndPreview() {
        WorkReportRoomEntity first = line(1L, "1.000", "0", report(RATE, 7L, "Alpha", FROM, FROM));
        WorkReportRoomEntity second = line(2L, "2.000", "0", report(RATE, 8L, "Beta", TO, TO));
        when(workReportRoomDao.findPayableLines(WORKER_ID, TO)).thenReturn(List.of(first, second));
        when(workerPayoutDao.findByWorker(WORKER_ID)).thenReturn(List.of(advance("50.00")));

        PayoutSuggestionDto dto = service.suggest(WORKER_ID, FROM, TO, false);

        assertThat(dto.gross()).isEqualByComparingTo("120.00");
        assertThat(dto.remainingCredit()).isEqualByComparingTo("50.00");
        assertThat(dto.appliedCredit()).isEqualByComparingTo("50.00");
        assertThat(dto.suggested()).isEqualByComparingTo("70.00");
        // suggested + applied = 120 covers both lines fully, oldest first.
        assertThat(dto.breakdown()).extracting(PayoutLineDto::allocationPreview)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("40.00"), new BigDecimal("80.00"));
    }

    @Test
    @DisplayName("seniority from the worker profile is applied and reported (R2.3, R4.6)")
    void seniorityFromProfile() {
        when(workerCostService.workerProfilesById(List.of(WORKER_ID)))
                .thenReturn(Map.of(WORKER_ID, new WorkerProfile(WORKER_ID, baseType, new BigDecimal("0.10"))));
        WorkReportRoomEntity l = line(10L, "1.000", "0", report(RATE, 7L, "Alpha", FROM, TO));
        when(workReportRoomDao.findPayableLines(WORKER_ID, TO)).thenReturn(List.of(l));

        PayoutLineDto b = service.suggest(WORKER_ID, FROM, TO, false).breakdown().get(0);

        assertThat(b.seniorityPct()).isEqualByComparingTo("0.10");
        assertThat(b.baseTierFallback()).isFalse();
        assertThat(b.costValue()).isGreaterThan(new BigDecimal("40.00"));
    }

    @Test
    @DisplayName("advance: planned basis across projects, no credit, nothing allocated (R4.7)")
    void advancePlannedBasis() {
        WorkReportEntity r1 = report(RATE, 7L, "Alpha", FROM, TO);
        WorkReportRoomEntity l1 = line(10L, "1.000", "0", r1);
        l1.setPlanQuantity(new BigDecimal("2.000"));
        r1.setRooms(new ArrayList<>(List.of(l1)));
        WorkReportEntity r2 = report(RATE, 8L, "Beta", FROM, TO);
        WorkReportRoomEntity l2 = line(11L, "1.000", "0", r2);
        r2.setRooms(new ArrayList<>(List.of(l2)));
        when(workReportDao.findApprovedForWorkerInPeriodWithRooms(WORKER_ID, FROM, TO))
                .thenReturn(List.of(r1, r2));
        when(workerPayoutDao.findByWorker(WORKER_ID)).thenReturn(List.of(advance("50.00")));

        PayoutSuggestionDto dto = service.suggest(WORKER_ID, FROM, TO, true);

        assertThat(dto.isAdvance()).isTrue();
        assertThat(dto.gross()).isEqualByComparingTo("120.00"); // 40×2 + 40×1 on plan quantity
        assertThat(dto.appliedCredit()).isEqualByComparingTo("0");
        assertThat(dto.suggested()).isEqualByComparingTo("120.00");
        assertThat(dto.breakdown()).extracting(PayoutLineDto::projectName).containsExactly("Alpha", "Beta");
        assertThat(dto.breakdown()).allSatisfy(b -> {
            assertThat(b.allocationPreview()).isEqualByComparingTo("0");
            assertThat(b.remainder()).isNull();
        });
        verify(workReportRoomDao, never()).findPayableLines(anyLong(), any());
    }

    @Test
    @DisplayName("payableLines returns locked candidates with positive remainder in order (R5.8)")
    void payableLinesForCreate() {
        WorkReportRoomEntity a = line(1L, "1.000", "10.00", report(RATE, 7L, "Alpha", FROM, FROM));
        WorkReportRoomEntity full = line(2L, "1.000", "40.00", report(RATE, 7L, "Alpha", FROM, FROM));
        WorkReportRoomEntity bad = line(3L, "1.000", "0", report(BigDecimal.ZERO, 7L, "Alpha", FROM, FROM));
        WorkReportRoomEntity b = line(4L, "1.000", "0", report(RATE, 8L, "Beta", TO, TO));
        when(workReportRoomDao.findPayableLinesForUpdate(WORKER_ID, TO)).thenReturn(List.of(a, full, bad, b));

        List<PayoutSuggestionService.PayableCandidate> c = service.payableLines(WORKER_ID, TO);

        assertThat(c).extracting(x -> x.line().getId()).containsExactly(1L, 4L);
        assertThat(c).extracting(PayoutSuggestionService.PayableCandidate::remainder)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("30.00"), new BigDecimal("40.00"));
        verify(workReportRoomDao, never()).findPayableLines(anyLong(), any());
    }

    // =======================================================================
    // fixtures
    // =======================================================================

    private static WorkReportEntity report(BigDecimal rate, Long projectId, String projectName,
                                           LocalDate from, LocalDate to) {
        WorkReportEntity r = new WorkReportEntity();
        r.setClientUnitPrice(rate);
        r.setPerformedFrom(from);
        r.setPerformedTo(to);
        UserEntity worker = new UserEntity();
        worker.setId(WORKER_ID);
        r.setWorker(worker);
        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setNamePL("Work item PL");
        workItem.setCode("WI");
        r.setWorkItem(workItem);
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setName(projectName);
        r.setProject(project);
        r.setRooms(new ArrayList<>());
        return r;
    }

    private static WorkReportRoomEntity line(Long id, String quantity, String paid, WorkReportEntity report) {
        WorkReportRoomEntity l = new WorkReportRoomEntity();
        l.setId(id);
        l.setQuantity(new BigDecimal(quantity));
        l.setPlanQuantity(new BigDecimal(quantity));
        l.setPaidAmount(new BigDecimal(paid));
        RoomEntity room = new RoomEntity();
        room.setLabel("Room label");
        l.setRoom(room);
        l.setReport(report);
        return l;
    }

    private static WorkerPayoutEntity advance(String amount) {
        WorkerPayoutEntity p = new WorkerPayoutEntity();
        p.setAdvance(true);
        p.setStatus(PayoutStatus.PLANNED);
        p.setAmount(new BigDecimal(amount));
        p.setAdvanceApplied(BigDecimal.ZERO);
        return p;
    }
}
