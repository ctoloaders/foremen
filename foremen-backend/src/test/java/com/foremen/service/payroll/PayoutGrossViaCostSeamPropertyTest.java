package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import com.foremen.dao.WorkReportDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.payroll.dto.PayoutLineDto;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.workercost.WorkerCostDerivation;
import com.foremen.service.workercost.WorkerCostService;
import com.foremen.service.estimate.margins.WorkerTypeTier;

/**
 * FOR-06-11 task 8.1 — <b>Property 1: GrossViaCostSeam</b> (design §Correctness Properties).
 *
 * <p>For any set of the worker's {@code APPROVED} report lines and any period, the gross computed by
 * {@link PayoutSuggestionService#suggest} equals the sum of the FOR-06-10 {@code Cost_Value} over
 * exactly the <b>unpaid</b> ({@code paidByPayout} null), available lines; an unavailable (null/zero
 * rate) line contributes nothing and is marked excluded, an already-paid line is never offered, and
 * adding an unavailable line leaves the gross unchanged. No second cost formula produces a different
 * number — the expectation re-folds the identical single seam ({@link WorkerCostDerivation}).
 *
 * <p><b>Validates: Requirements 3.1, 3.4</b>
 */
@Tag("Feature: FOR-06-11-payroll, Property 1: gross == Σ FOR-06-10 Cost_Value over the unpaid in-period available lines; unavailable excluded")
class PayoutGrossViaCostSeamPropertyTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long WORKER_ID = 500L;
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 1, 31);
    private static final BigDecimal BASE_SHARE = new BigDecimal("0.40");

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 1: gross equals the summed single-seam Cost_Value over available unpaid lines")
    void grossEqualsSummedCostValueOverAvailableUnpaidLines(@ForAll("lineSpecs") List<LineSpec> specs) {
        List<WorkReportEntity> reports = buildReports(specs);
        PayoutSuggestionService service = newService(reports);

        PayoutSuggestionDto dto = service.suggest(PROJECT_ID, WORKER_ID, FROM, TO, false);

        // Independently re-fold the SINGLE seam over exactly the available, unpaid lines.
        BigDecimal expected = BigDecimal.ZERO;
        WorkerTypeTier baseTier = WorkerTypeTier.base(BASE_SHARE);
        for (LineSpec s : specs) {
            if (s.paid) {
                continue; // an already-paid line is never offered (R6.2)
            }
            BigDecimal cost = WorkerCostDerivation.costValue(s.rate, baseTier, baseTier, s.quantity);
            if (cost != null) {
                expected = expected.add(cost); // only an available line contributes (R3.4)
            }
        }

        assertThat(dto.gross())
                .as("gross == Σ Cost_Value over available unpaid in-period lines (R3.1, R3.4)")
                .isEqualByComparingTo(expected);

        // Every unavailable line is in the breakdown, marked excluded, with a null cost.
        for (PayoutLineDto line : dto.breakdown()) {
            if (!line.available()) {
                assertThat(line.costValue())
                        .as("an unavailable line has a null cost (never a fabricated zero) (R3.4)")
                        .isNull();
            }
        }
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 1: adding an unavailable line leaves the gross unchanged")
    void addingAnUnavailableLineLeavesGrossUnchanged(
            @ForAll("lineSpecs") List<LineSpec> specs,
            @ForAll("quantity") BigDecimal extraQty) {

        BigDecimal before = newService(buildReports(specs))
                .suggest(PROJECT_ID, WORKER_ID, FROM, TO, false).gross();

        // append one unpaid, zero-rate (unavailable) line.
        List<LineSpec> withUnavailable = new ArrayList<>(specs);
        withUnavailable.add(new LineSpec(BigDecimal.ZERO, extraQty, false));
        BigDecimal after = newService(buildReports(withUnavailable))
                .suggest(PROJECT_ID, WORKER_ID, FROM, TO, false).gross();

        assertThat(after)
                .as("adding an unavailable line leaves the gross unchanged (R3.4)")
                .isEqualByComparingTo(before);
    }

    // =======================================================================
    // wiring
    // =======================================================================

    private PayoutSuggestionService newService(List<WorkReportEntity> reports) {
        WorkReportDao workReportDao = mock(WorkReportDao.class);
        WorkerPayoutDao workerPayoutDao = mock(WorkerPayoutDao.class);
        WorkerCostService workerCostService = mock(WorkerCostService.class);

        WorkerTypeEntity baseType = new WorkerTypeEntity();
        baseType.setId(1L);
        baseType.setBase(true);
        baseType.setActive(true);
        baseType.setTierPct(BASE_SHARE);
        baseType.setCode("BASE");
        lenient().when(workerCostService.findBaseType()).thenReturn(baseType);
        lenient().when(workerCostService.activeWorkerTypesByWorkerId(PROJECT_ID)).thenReturn(Map.of());
        lenient().when(workReportDao.findApprovedInPeriodWithRooms(anyLong(), anyLong(), any(), any()))
                .thenReturn(reports);
        // no prior payouts -> zero remaining credit, so the credit layer does not perturb the gross.
        lenient().when(workerPayoutDao.findByProjectAndWorker(anyLong(), anyLong()))
                .thenReturn(List.<WorkerPayoutEntity>of());

        return new PayoutSuggestionService(
                workReportDao, workerPayoutDao, workerCostService, new AdvanceCreditCalculator());
    }

    private static List<WorkReportEntity> buildReports(List<LineSpec> specs) {
        List<WorkReportEntity> reports = new ArrayList<>();
        long id = 1;
        for (LineSpec s : specs) {
            WorkReportEntity r = new WorkReportEntity();
            r.setClientUnitPrice(s.rate);
            r.setPerformedFrom(FROM);
            r.setPerformedTo(TO);
            UserEntity worker = new UserEntity();
            worker.setId(WORKER_ID);
            r.setWorker(worker);
            WorkItemEntity wi = new WorkItemEntity();
            wi.setNamePL("WI");
            wi.setCode("WI");
            r.setWorkItem(wi);

            WorkReportRoomEntity line = new WorkReportRoomEntity();
            line.setId(id++);
            line.setQuantity(s.quantity);
            line.setPlanQuantity(s.quantity);
            RoomEntity room = new RoomEntity();
            room.setLabel("Room");
            line.setRoom(room);
            line.setReport(r);
            if (s.paid) {
                WorkerPayoutEntity payer = new WorkerPayoutEntity();
                payer.setId(9000L);
                line.setPaidByPayout(payer); // an already-paid line (R6.2)
            }
            List<WorkReportRoomEntity> rooms = new ArrayList<>();
            rooms.add(line);
            r.setRooms(rooms);
            reports.add(r);
        }
        return reports;
    }

    // =======================================================================
    // generators
    // =======================================================================

    /** One report line: its rate (0 ⇒ unavailable), reported quantity, and whether it is already paid. */
    record LineSpec(BigDecimal rate, BigDecimal quantity, boolean paid) {
    }

    @Provide
    Arbitrary<BigDecimal> rate() {
        // Include 0 so unavailable lines occur; otherwise a positive two-decimal price.
        Arbitrary<BigDecimal> positive = Arbitraries.longs().between(1L, 100_000L)
                .map(c -> BigDecimal.valueOf(c, 2));
        return Arbitraries.oneOf(positive, Arbitraries.just(BigDecimal.ZERO));
    }

    @Provide
    Arbitrary<BigDecimal> quantity() {
        return Arbitraries.longs().between(1L, 1_000_000L).map(c -> BigDecimal.valueOf(c, 3));
    }

    @Provide
    Arbitrary<LineSpec> lineSpec() {
        return Combinators.combine(rate(), quantity(), Arbitraries.of(true, false))
                .as(LineSpec::new);
    }

    @Provide
    Arbitrary<List<LineSpec>> lineSpecs() {
        return lineSpec().list().ofMinSize(0).ofMaxSize(8);
    }
}
