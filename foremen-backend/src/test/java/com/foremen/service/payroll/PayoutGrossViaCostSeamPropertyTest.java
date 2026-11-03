package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.foremen.dao.WorkReportRoomDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.estimate.margins.WorkerTypeTier;
import com.foremen.service.payroll.dto.PayoutLineDto;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.workercost.WorkerCostDerivation;
import com.foremen.service.workercost.WorkerCostService;

/**
 * <b>GrossViaCostSeam</b> (FOR-06-11 Property 1, reshaped by FOR-07-06 5.1 to the worker-wide,
 * remainder-based suggestion).
 *
 * <p>For any set of the worker's candidate lines (any project, any partial paid amount), the gross
 * equals {@code Σ max(0, Cost_Value − paidAmount)} over exactly the lines with an available cost,
 * where {@code Cost_Value} is re-folded through the single seam ({@link WorkerCostDerivation}); an
 * unavailable line is marked and contributes nothing, a fully paid line is not listed, and adding an
 * unavailable line leaves the gross unchanged.
 *
 * <p><b>Validates: Requirements 4.2, 4.4</b>
 */
@Tag("Feature: FOR-07-06-company-payroll, Property: gross == Σ unpaid remainder over available candidate lines")
class PayoutGrossViaCostSeamPropertyTest {

    private static final Long WORKER_ID = 500L;
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 1, 31);
    private static final BigDecimal BASE_SHARE = new BigDecimal("0.40");

    @Property(tries = 200)
    void grossEqualsSummedRemainderOverAvailableLines(@ForAll("lineSpecs") List<LineSpec> specs) {
        PayoutSuggestionDto dto = newService(buildLines(specs)).suggest(WORKER_ID, FROM, TO, false);

        BigDecimal expected = BigDecimal.ZERO;
        int expectedListed = 0;
        WorkerTypeTier baseTier = WorkerTypeTier.base(BASE_SHARE);
        for (LineSpec s : specs) {
            BigDecimal cost = WorkerCostDerivation.costValue(s.rate, baseTier, baseTier, s.quantity);
            if (cost == null) {
                expectedListed++; // unavailable: listed, excluded
                continue;
            }
            BigDecimal remainder = cost.subtract(paidOf(s, cost)).max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
            if (remainder.signum() > 0) {
                expected = expected.add(remainder);
                expectedListed++;
            }
        }

        assertThat(dto.gross()).isEqualByComparingTo(expected);
        assertThat(dto.breakdown()).hasSize(expectedListed);
        for (PayoutLineDto line : dto.breakdown()) {
            if (!line.available()) {
                assertThat(line.costValue()).isNull();
                assertThat(line.remainder()).isNull();
            } else {
                assertThat(line.remainder()).isPositive();
            }
        }
    }

    @Property(tries = 200)
    void addingAnUnavailableLineLeavesGrossUnchanged(
            @ForAll("lineSpecs") List<LineSpec> specs,
            @ForAll("quantity") BigDecimal extraQty) {

        BigDecimal before = newService(buildLines(specs)).suggest(WORKER_ID, FROM, TO, false).gross();

        List<LineSpec> withUnavailable = new ArrayList<>(specs);
        withUnavailable.add(new LineSpec(BigDecimal.ZERO, extraQty, PaidMode.NONE));
        BigDecimal after = newService(buildLines(withUnavailable)).suggest(WORKER_ID, FROM, TO, false).gross();

        assertThat(after).isEqualByComparingTo(before);
    }

    // =======================================================================
    // wiring
    // =======================================================================

    private PayoutSuggestionService newService(List<WorkReportRoomEntity> lines) {
        WorkReportDao workReportDao = mock(WorkReportDao.class);
        WorkReportRoomDao workReportRoomDao = mock(WorkReportRoomDao.class);
        WorkerPayoutDao workerPayoutDao = mock(WorkerPayoutDao.class);
        WorkerCostService workerCostService = mock(WorkerCostService.class);

        WorkerTypeEntity baseType = new WorkerTypeEntity();
        baseType.setId(1L);
        baseType.setBase(true);
        baseType.setActive(true);
        baseType.setTierPct(BASE_SHARE);
        baseType.setCode("BASE");
        lenient().when(workerCostService.findBaseType()).thenReturn(baseType);
        lenient().when(workerCostService.workerProfilesById(any())).thenReturn(Map.of());
        lenient().when(workReportRoomDao.findPayableLines(anyLong(), any())).thenReturn(lines);
        lenient().when(workerPayoutDao.findByWorker(anyLong())).thenReturn(List.<WorkerPayoutEntity>of());

        return new PayoutSuggestionService(workReportDao, workReportRoomDao, workerPayoutDao,
                workerCostService, new AdvanceCreditCalculator());
    }

    private static List<WorkReportRoomEntity> buildLines(List<LineSpec> specs) {
        List<WorkReportRoomEntity> lines = new ArrayList<>();
        WorkerTypeTier baseTier = WorkerTypeTier.base(BASE_SHARE);
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
            ProjectEntity project = new ProjectEntity();
            project.setId(id % 3 + 1); // several projects: the suggestion is worker-wide
            r.setProject(project);

            WorkReportRoomEntity line = new WorkReportRoomEntity();
            line.setId(id++);
            line.setQuantity(s.quantity);
            line.setPlanQuantity(s.quantity);
            RoomEntity room = new RoomEntity();
            room.setLabel("Room");
            line.setRoom(room);
            line.setReport(r);
            BigDecimal cost = WorkerCostDerivation.costValue(s.rate, baseTier, baseTier, s.quantity);
            line.setPaidAmount(paidOf(s, cost));
            lines.add(line);
        }
        return lines;
    }

    /** The paid amount for a spec: none, half (rounded down), or the full cost. */
    private static BigDecimal paidOf(LineSpec s, BigDecimal cost) {
        if (cost == null) {
            return BigDecimal.ZERO;
        }
        return switch (s.paid) {
            case NONE -> BigDecimal.ZERO;
            case PARTIAL -> cost.divide(BigDecimal.valueOf(2), 2, RoundingMode.DOWN);
            case FULL -> cost;
        };
    }

    // =======================================================================
    // generators
    // =======================================================================

    enum PaidMode { NONE, PARTIAL, FULL }

    /** One candidate line: rate (0 ⇒ unavailable), reported quantity, and how much is already paid. */
    record LineSpec(BigDecimal rate, BigDecimal quantity, PaidMode paid) {
    }

    @Provide
    Arbitrary<BigDecimal> rate() {
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
        return Combinators.combine(rate(), quantity(), Arbitraries.of(PaidMode.class)).as(LineSpec::new);
    }

    @Provide
    Arbitrary<List<LineSpec>> lineSpecs() {
        return lineSpec().list().ofMinSize(0).ofMaxSize(8);
    }
}
