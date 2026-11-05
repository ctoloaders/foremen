package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import com.foremen.dao.UserDao;
import com.foremen.dao.WorkReportRoomDao;
import com.foremen.dao.WorkerPayoutAllocationDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.WorkerPayrollLineDao;
import com.foremen.dao.WorkerPayrollProfileDao;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkerPayoutAllocationEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.dao.model.WorkerPayrollLineEntity;
import com.foremen.dao.model.WorkerPayrollProfileEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.payroll.dto.CompanyPayrollDto;
import com.foremen.service.payroll.dto.CompanyPayrollProjectRowDto;
import com.foremen.service.payroll.dto.CompanyPayrollRowDto;
import com.foremen.service.payroll.dto.PayrollEarnedLineDto;
import com.foremen.service.payroll.dto.PayrollEarnedProjectDto;
import com.foremen.service.payroll.dto.PayrollLineDto;
import com.foremen.service.payroll.dto.PayrollMonthDto;
import com.foremen.service.payroll.dto.WorkerPayrollCardDto;
import com.foremen.service.payroll.dto.mapper.WorkerPayoutDtoMapperImpl;
import com.foremen.service.workercost.WorkerCostService;
import com.foremen.service.workercost.WorkerProfile;

/**
 * FOR-07-06 task 5.4 — Mockito unit tests for {@link PayrollReadModelService} (design
 * §PayrollReadModelService; Requirements 7.3, 9.3, 9.4, 10.1–10.6, 11.1–11.3). DAOs and the cost seam
 * are mocked; {@link AdvanceCreditCalculator}, {@link BalanceCalculator}, {@link MonthAttribution},
 * {@link PayrollHintSelector} and the payout DTO mapper are real.
 *
 * <p>Base tier 0.40, rate 100 ⇒ unit cost 40.00 per unit of quantity.
 *
 * <p>Fixture for worker Anna (id 1, base type, seniority 0), period 2026-02-01 … 2026-03-31, today
 * 2026-03-15, opening balance 100:
 * <pre>
 *   lines:      L1 Jan-15 Alpha q1 → 40 | L2 Feb-10 Alpha q2 plan3 → 80 / 120, paid 50
 *               L3 Mar-05 Beta q1 → 40  | L4 Mar-06 Beta rate 0 → unavailable
 *   payouts:    P1 Jan-20 30 (PAID)     | P2 Mar-01 50 (PLANNED), allocated 50 on L2 (Alpha)
 *   additional: Jan Multisport 10 | Feb Multisport 10 | Feb ZUS −7 | Mar Zus −5
 * </pre>
 * Balance end of Jan = 100 + 40 + 10 − 30 = 120; Feb = 120 + 83 = 203; Mar = 203 + 35 − 50 = 188.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayrollReadModelServiceTest {

    private static final Long ANNA = 1L;
    private static final Long BOB = 2L;
    private static final Long MANAGER = 3L;
    private static final LocalDate FROM = LocalDate.of(2026, 2, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 31);
    private static final BigDecimal RATE = new BigDecimal("100.00");

    @Mock
    private UserDao userDao;
    @Mock
    private WorkReportRoomDao workReportRoomDao;
    @Mock
    private WorkerPayoutDao workerPayoutDao;
    @Mock
    private WorkerPayoutAllocationDao allocationDao;
    @Mock
    private WorkerPayrollLineDao payrollLineDao;
    @Mock
    private WorkerPayrollProfileDao payrollProfileDao;
    @Mock
    private WorkerCostService workerCostService;

    private PayrollReadModelService service;
    private WorkerTypeEntity baseType;
    private UserEntity anna;
    private UserEntity bob;
    private UserEntity manager;
    private ProjectEntity alpha;
    private ProjectEntity beta;
    private WorkReportRoomEntity l2;

    @BeforeEach
    void setUp() {
        service = new PayrollReadModelService(userDao, workReportRoomDao, workerPayoutDao, allocationDao,
                payrollLineDao, payrollProfileDao, workerCostService, new AdvanceCreditCalculator(),
                new WorkerPayoutDtoMapperImpl());
        service.setClock(Clock.fixed(LocalDate.of(2026, 3, 15).atStartOfDay().toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC));

        baseType = new WorkerTypeEntity();
        baseType.setId(10L);
        baseType.setBase(true);
        baseType.setActive(true);
        baseType.setTierPct(new BigDecimal("0.40"));
        baseType.setCode("BASE");
        lenient().when(workerCostService.findBaseType()).thenReturn(baseType);
        // Anna has the base type; Bob has no profile ⇒ base-tier fallback.
        lenient().when(workerCostService.workerProfilesById(any()))
                .thenReturn(Map.of(ANNA, new WorkerProfile(ANNA, baseType, BigDecimal.ZERO)));

        anna = user(ANNA, "Anna", "WORKER");
        bob = user(BOB, "Bob", "WORKER");
        manager = user(MANAGER, "Mia", "MANAGER");
        lenient().when(userDao.findByRoleCode("WORKER")).thenReturn(List.of(bob, anna));
        lenient().when(userDao.findById(ANNA)).thenReturn(Optional.of(anna));
        lenient().when(userDao.findById(MANAGER)).thenReturn(Optional.of(manager));

        alpha = project(7L, "Alpha");
        beta = project(8L, "Beta");
        WorkReportRoomEntity l1 = line(101L, "1.000", "1.000", "0", report(RATE, alpha, LocalDate.of(2026, 1, 15)));
        l2 = line(102L, "2.000", "3.000", "50.00", report(RATE, alpha, LocalDate.of(2026, 2, 10)));
        WorkReportRoomEntity l3 = line(103L, "1.000", "1.000", "0", report(RATE, beta, LocalDate.of(2026, 3, 5)));
        WorkReportRoomEntity l4 = line(104L, "1.000", "1.000", "0",
                report(BigDecimal.ZERO, beta, LocalDate.of(2026, 3, 6)));
        lenient().when(workReportRoomDao.findApprovedLinesForWorkers(anyCollection(), eq(TO)))
                .thenReturn(List.of(l1, l2, l3, l4));

        WorkerPayoutEntity p1 = payout(201L, LocalDate.of(2026, 1, 20), "30.00", PayoutStatus.PAID);
        WorkerPayoutEntity p2 = payout(202L, LocalDate.of(2026, 3, 1), "50.00", PayoutStatus.PLANNED);
        lenient().when(workerPayoutDao.findByWorkersAndPayoutDateLte(anyCollection(), eq(TO)))
                .thenReturn(List.of(p1, p2));
        WorkerPayoutAllocationEntity a = new WorkerPayoutAllocationEntity();
        a.setPayout(p2);
        a.setWorkReportRoom(l2);
        a.setAmount(new BigDecimal("50.00"));
        lenient().when(allocationDao.findByPayoutIds(anyCollection())).thenReturn(List.of(a));

        lenient().when(payrollLineDao.findByWorkerIdsAndMonthBetween(anyCollection(), any(), eq(TO)))
                .thenReturn(List.of(
                        additional(301L, LocalDate.of(2026, 1, 1), "Multisport", "10.00"),
                        additional(302L, LocalDate.of(2026, 2, 1), "Multisport", "10.00"),
                        additional(303L, LocalDate.of(2026, 2, 1), "ZUS", "-7.00"),
                        additional(304L, LocalDate.of(2026, 3, 1), "Zus", "-5.00")));

        WorkerPayrollProfileEntity profile = new WorkerPayrollProfileEntity();
        profile.setWorker(anna);
        profile.setOpeningBalance(new BigDecimal("100.00"));
        lenient().when(payrollProfileDao.findByWorkerIds(anyCollection())).thenReturn(List.of(profile));
    }

    // =============================================================================================
    // Company table
    // =============================================================================================

    @Test
    @DisplayName("table row: period sums, project sub-rows, balance, status; all workers listed (R10.2–10.4, bug E)")
    void tableRowSumsSubRowsAndBalance() {
        CompanyPayrollDto dto = service.companyTable(FROM, TO, List.of());

        assertThat(dto.from()).isEqualTo(FROM);
        assertThat(dto.to()).isEqualTo(TO);
        // Bug E (planning mode, default): every WORKER is listed, including zero-activity Bob,
        // ordered by name (Anna, then Bob).
        assertThat(dto.rows()).extracting(CompanyPayrollRowDto::workerId).containsExactly(ANNA, BOB);
        CompanyPayrollRowDto row = dto.rows().get(0);
        assertThat(row.workerName()).isEqualTo("Anna");
        assertThat(row.workerTypeName()).isEqualTo("BASE");
        assertThat(row.workerTypeMissing()).isFalse();
        assertThat(row.seniorityPct()).isEqualByComparingTo("0");
        // L2 + L3 in the period (L1 is January, L4 unavailable).
        assertThat(row.sums().reported()).isEqualByComparingTo("120.00");
        assertThat(row.sums().planned()).isEqualByComparingTo("160.00");
        assertThat(row.sums().other()).isEqualByComparingTo("-2.00");
        assertThat(row.sums().earned()).isEqualByComparingTo("118.00");
        assertThat(row.sums().paid()).isEqualByComparingTo("50.00");
        assertThat(row.balanceAtTo()).isEqualByComparingTo("188.00");
        // planned (160) > reported (120) but the balance is positive → HAS_DEBT wins (bug E precedence).
        assertThat(row.status()).isEqualTo(CompanyPaymentStatus.HAS_DEBT);

        // The zero-activity filler row (bug E): zeroed sums, empty projects, NO_ACTIVITY status.
        CompanyPayrollRowDto bobRow = dto.rows().get(1);
        assertThat(bobRow.workerName()).isEqualTo("Bob");
        assertThat(bobRow.sums().earned()).isEqualByComparingTo("0");
        assertThat(bobRow.sums().paid()).isEqualByComparingTo("0");
        assertThat(bobRow.balanceAtTo()).isEqualByComparingTo("0");
        assertThat(bobRow.projects()).isEmpty();
        assertThat(bobRow.status()).isEqualTo(CompanyPaymentStatus.NO_ACTIVITY);

        assertThat(row.projects()).extracting(CompanyPayrollProjectRowDto::projectName)
                .containsExactly("Alpha", "Beta");
        CompanyPayrollProjectRowDto a = row.projects().get(0);
        assertThat(a.reported()).isEqualByComparingTo("80.00");
        assertThat(a.planned()).isEqualByComparingTo("120.00");
        assertThat(a.paid()).isEqualByComparingTo("50.00");
        CompanyPayrollProjectRowDto b = row.projects().get(1);
        assertThat(b.reported()).isEqualByComparingTo("40.00");
        assertThat(b.planned()).isEqualByComparingTo("40.00");
        assertThat(b.paid()).isEqualByComparingTo("0");

        // Totals over the visible rows only (R10.5).
        assertThat(dto.totals().earned()).isEqualByComparingTo("118.00");
        assertThat(dto.totals().paid()).isEqualByComparingTo("50.00");
        assertThat(dto.totals().reported()).isEqualByComparingTo("120.00");
        assertThat(dto.totals().planned()).isEqualByComparingTo("160.00");
        assertThat(dto.totals().other()).isEqualByComparingTo("-2.00");
    }

    @Test
    @DisplayName("worker filter: listed WORKERs shown even without activity, non-WORKERs dropped, fallback flagged")
    void workerFilterListsWorkersAndFlagsMissingType() {
        when(userDao.findWorkerProfiles(anyCollection())).thenReturn(List.of(anna, bob, manager));

        CompanyPayrollDto dto = service.companyTable(FROM, TO, List.of(ANNA, BOB, MANAGER));

        assertThat(dto.rows()).extracting(CompanyPayrollRowDto::workerName).containsExactly("Anna", "Bob");
        CompanyPayrollRowDto bobRow = dto.rows().get(1);
        assertThat(bobRow.workerTypeMissing()).isTrue();
        assertThat(bobRow.workerTypeName()).isEqualTo("BASE");
        assertThat(bobRow.sums().earned()).isEqualByComparingTo("0");
        assertThat(bobRow.sums().paid()).isEqualByComparingTo("0");
        assertThat(bobRow.balanceAtTo()).isEqualByComparingTo("0");
        assertThat(bobRow.projects()).isEmpty();
        verify(userDao, never()).findByRoleCode(any());
    }

    @Test
    @DisplayName("no period payouts ⇒ allocation query skipped; zero-activity period still lists all workers (bug E)")
    void emptyPeriodStillListsAllWorkers() {
        LocalDate from = LocalDate.of(2025, 6, 1);
        LocalDate to = LocalDate.of(2025, 6, 30);

        CompanyPayrollDto dto = service.companyTable(from, to, null);

        // Bug E: every WORKER is listed even in a period with no data. Anna still carries her
        // opening balance of 100 (owed) ⇒ HAS_DEBT; Bob has no opening balance ⇒ NO_ACTIVITY.
        assertThat(dto.rows()).extracting(CompanyPayrollRowDto::workerName).containsExactly("Anna", "Bob");
        CompanyPayrollRowDto annaRow = dto.rows().get(0);
        assertThat(annaRow.sums().earned()).isEqualByComparingTo("0");
        assertThat(annaRow.balanceAtTo()).isEqualByComparingTo("100.00");
        assertThat(annaRow.status()).isEqualTo(CompanyPaymentStatus.HAS_DEBT);
        CompanyPayrollRowDto bobRow = dto.rows().get(1);
        assertThat(bobRow.balanceAtTo()).isEqualByComparingTo("0");
        assertThat(bobRow.status()).isEqualTo(CompanyPaymentStatus.NO_ACTIVITY);
        assertThat(dto.totals().earned()).isEqualByComparingTo("0");
        verify(allocationDao, never()).findByPayoutIds(anyCollection());
    }

    @Test
    @DisplayName("from > to ⇒ 422 error.payroll.period.invalid (R10.1)")
    void invalidPeriodRejected() {
        assertThatThrownBy(() -> service.companyTable(TO, FROM, List.of()))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(e -> {
                    ForemenApiException ex = (ForemenApiException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getMessage()).isEqualTo(PayrollReadModelService.PERIOD_INVALID);
                });
        assertThatThrownBy(() -> service.workerCard(ANNA, TO, FROM)).isInstanceOf(ForemenApiException.class);
    }

    // =============================================================================================
    // Worker card
    // =============================================================================================

    @Test
    @DisplayName("card: month blocks, previous balance chain, line drill-down, payouts (R11.1, R11.2, R9.3)")
    void cardMonthBlocks() {
        when(workerPayoutDao.findByWorker(ANNA)).thenReturn(List.of(advance("70.00")));

        WorkerPayrollCardDto card = service.workerCard(ANNA, FROM, TO);

        assertThat(card.workerName()).isEqualTo("Anna");
        assertThat(card.workerTypeMissing()).isFalse();
        assertThat(card.openingBalance()).isEqualByComparingTo("100.00");
        assertThat(card.remainingAdvanceCredit()).isEqualByComparingTo("70.00");
        assertThat(card.months()).extracting(PayrollMonthDto::month)
                .containsExactly(YearMonth.of(2026, 2), YearMonth.of(2026, 3));

        PayrollMonthDto feb = card.months().get(0);
        assertThat(feb.previousBalance()).isEqualByComparingTo("120.00");
        assertThat(feb.earned()).extracting(PayrollEarnedProjectDto::projectName).containsExactly("Alpha");
        assertThat(feb.earned().get(0).amount()).isEqualByComparingTo("80.00");
        PayrollEarnedLineDto line = feb.earned().get(0).lines().get(0);
        assertThat(line.workReportRoomId()).isEqualTo(102L);
        assertThat(line.workItemName()).isEqualTo("Work item PL");
        assertThat(line.roomName()).isEqualTo("Room label");
        assertThat(line.unit()).isEqualTo("m2");
        assertThat(line.unitCost()).isEqualByComparingTo("40.00");
        assertThat(line.quantity()).isEqualByComparingTo("2.000");
        assertThat(line.cost()).isEqualByComparingTo("80.00");
        assertThat(line.paidAmount()).isEqualByComparingTo("50.00");
        assertThat(line.paymentStatus()).isEqualTo(LinePaymentStatus.PARTIAL);
        assertThat(feb.additionalLines()).extracting(PayrollLineDto::name).containsExactly("Multisport", "ZUS");
        assertThat(feb.total()).isEqualByComparingTo("83.00");
        assertThat(feb.payouts()).isEmpty();
        assertThat(feb.paid()).isEqualByComparingTo("0");
        assertThat(feb.balance()).isEqualByComparingTo("203.00");
        assertThat(feb.hints()).isEmpty(); // not the current month

        PayrollMonthDto mar = card.months().get(1);
        assertThat(mar.previousBalance()).isEqualByComparingTo("203.00");
        assertThat(mar.earned()).hasSize(1);
        PayrollEarnedProjectDto betaRow = mar.earned().get(0);
        assertThat(betaRow.projectName()).isEqualTo("Beta");
        assertThat(betaRow.amount()).isEqualByComparingTo("40.00"); // L4 unavailable, excluded
        assertThat(betaRow.lines()).extracting(PayrollEarnedLineDto::paymentStatus)
                .containsExactly(LinePaymentStatus.UNPAID, LinePaymentStatus.UNAVAILABLE);
        assertThat(betaRow.lines().get(1).cost()).isNull();
        assertThat(mar.total()).isEqualByComparingTo("35.00");
        assertThat(mar.payouts()).hasSize(1);
        assertThat(mar.payouts().get(0).id()).isEqualTo(202L);
        assertThat(mar.payouts().get(0).payoutDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(mar.paid()).isEqualByComparingTo("50.00");
        assertThat(mar.balance()).isEqualByComparingTo("188.00");
        // balance = previous + total − paid in every block.
        for (PayrollMonthDto m : card.months()) {
            assertThat(m.balance()).isEqualByComparingTo(m.previousBalance().add(m.total()).subtract(m.paid()));
        }
    }

    @Test
    @DisplayName("card hints only in the current month: M−1 names absent in M (R11.3, R8.5)")
    void cardHintsInCurrentMonth() {
        WorkerPayrollCardDto card = service.workerCard(ANNA, FROM, TO);

        PayrollMonthDto mar = card.months().get(1);
        assertThat(mar.hints()).extracting(PayrollLineDto::name).containsExactly("Multisport");
        assertThat(mar.hints().get(0).month()).isEqualTo(LocalDate.of(2026, 2, 1));
    }

    @Test
    @DisplayName("table balanceAtTo equals the card's last block balance (Property 7)")
    void tableBalanceEqualsCardLastBlock() {
        CompanyPayrollRowDto row = service.companyTable(FROM, TO, List.of()).rows().get(0);
        List<PayrollMonthDto> months = service.workerCard(ANNA, FROM, TO).months();

        assertThat(row.balanceAtTo()).isEqualByComparingTo(months.get(months.size() - 1).balance());

        // Also for a mid-month window.
        LocalDate from = LocalDate.of(2026, 1, 10);
        LocalDate to = LocalDate.of(2026, 2, 20);
        when(workReportRoomDao.findApprovedLinesForWorkers(anyCollection(), eq(to))).thenReturn(List.of());
        when(workerPayoutDao.findByWorkersAndPayoutDateLte(anyCollection(), eq(to))).thenReturn(List.of());
        when(payrollLineDao.findByWorkerIdsAndMonthBetween(anyCollection(), any(), eq(to))).thenReturn(List.of(
                additional(401L, LocalDate.of(2026, 1, 1), "Bonus", "15.00"),
                additional(402L, LocalDate.of(2026, 2, 1), "Bonus", "5.00")));
        CompanyPayrollRowDto midRow = service.companyTable(from, to, List.of()).rows().get(0);
        List<PayrollMonthDto> midMonths = service.workerCard(ANNA, from, to).months();
        assertThat(midRow.balanceAtTo()).isEqualByComparingTo("120.00");
        assertThat(midRow.balanceAtTo()).isEqualByComparingTo(midMonths.get(midMonths.size() - 1).balance());
    }

    @Test
    @DisplayName("card for a non-WORKER ⇒ 422 error.workerPayout.worker.invalid")
    void cardRejectsNonWorker() {
        assertThatThrownBy(() -> service.workerCard(MANAGER, FROM, TO))
                .isInstanceOf(ForemenApiException.class)
                .hasMessage(PayrollWriteSupport.WORKER_INVALID);
        verify(workReportRoomDao, never()).findApprovedLinesForWorkers(anyCollection(), any());
        verify(workerPayoutDao, never()).findByWorker(anyLong());
    }

    // =============================================================================================
    // fixtures
    // =============================================================================================

    private static UserEntity user(Long id, String name, String roleCode) {
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setName(name);
        u.setRole(role);
        return u;
    }

    private static ProjectEntity project(Long id, String name) {
        ProjectEntity p = new ProjectEntity();
        p.setId(id);
        p.setName(name);
        return p;
    }

    private WorkReportEntity report(BigDecimal rate, ProjectEntity project, LocalDate performed) {
        WorkReportEntity r = new WorkReportEntity();
        r.setClientUnitPrice(rate);
        r.setPerformedFrom(performed);
        r.setPerformedTo(performed);
        r.setWorker(anna);
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode("M2");
        unit.setNamePL("m2");
        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setNamePL("Work item PL");
        workItem.setCode("WI");
        workItem.setUnit(unit);
        r.setWorkItem(workItem);
        r.setProject(project);
        r.setRooms(new ArrayList<>());
        return r;
    }

    private static WorkReportRoomEntity line(Long id, String quantity, String planQuantity, String paid,
                                             WorkReportEntity report) {
        WorkReportRoomEntity l = new WorkReportRoomEntity();
        l.setId(id);
        l.setQuantity(new BigDecimal(quantity));
        l.setPlanQuantity(new BigDecimal(planQuantity));
        l.setPaidAmount(new BigDecimal(paid));
        RoomEntity room = new RoomEntity();
        room.setLabel("Room label");
        l.setRoom(room);
        l.setReport(report);
        return l;
    }

    private WorkerPayoutEntity payout(Long id, LocalDate date, String amount, PayoutStatus status) {
        WorkerPayoutEntity p = new WorkerPayoutEntity();
        p.setId(id);
        p.setWorker(anna);
        p.setPayoutDate(date);
        p.setPeriodFrom(date.withDayOfMonth(1));
        p.setPeriodTo(date);
        p.setAmount(new BigDecimal(amount));
        p.setSuggestedAmount(new BigDecimal(amount));
        p.setAdvanceApplied(BigDecimal.ZERO);
        p.setPaymentChannel(PaymentChannel.values()[0]);
        p.setStatus(status);
        return p;
    }

    private WorkerPayoutEntity advance(String amount) {
        WorkerPayoutEntity p = payout(299L, LocalDate.of(2026, 3, 10), amount, PayoutStatus.PLANNED);
        p.setAdvance(true);
        return p;
    }

    private WorkerPayrollLineEntity additional(Long id, LocalDate month, String name, String amount) {
        WorkerPayrollLineEntity l = new WorkerPayrollLineEntity();
        l.setId(id);
        l.setWorker(anna);
        l.setMonth(month);
        l.setName(name);
        l.setAmount(new BigDecimal(amount));
        return l;
    }
}
