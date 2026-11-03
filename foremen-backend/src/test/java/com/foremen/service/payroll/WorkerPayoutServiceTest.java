package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerPayoutAllocationDao;
import com.foremen.dao.WorkerPayoutDao;
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
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.WorkerPayoutServiceMapper;
import com.foremen.service.payroll.PayoutSuggestionService.PayableCandidate;
import com.foremen.service.payroll.dto.CreatePayoutRequest;
import com.foremen.service.payroll.dto.PayoutAllocationDto;
import com.foremen.service.payroll.dto.PayoutFilter;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.payroll.dto.WorkerPayoutDto;
import com.foremen.service.payroll.dto.mapper.WorkerPayoutDtoMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-07-06 task 5.2 — Mockito unit tests for the company-wide {@link WorkerPayoutService}
 * (design §WorkerPayoutService). DAOs, the suggestion service, mappers, and the {@link EntityManager}
 * are mocked; the {@link AllocationWriter}, {@link PayoutDistributor}, {@link AdvanceCreditCalculator},
 * and {@link AdvanceDeriver} are real, so the distribution / credit / paid-amount figures are the
 * production ones.
 *
 * <ul>
 *   <li>create — oldest-first distribution with a partial last line, credit applied worker-wide,
 *       surplus unallocated, payout date default/override, advance allocates nothing (R5.1–R5.6);</li>
 *   <li>create — validation order and codes (R5.7);</li>
 *   <li>suggest — period/worker validation and delegation without a project (R4.1, R4.8);</li>
 *   <li>lifecycle — mark-paid, PAID terminal, regular delete releases allocations, worker-wide
 *       consumed-advance check (R6.1–R6.4);</li>
 *   <li>reads — list filter + allocations; not project-scoped (R14.2); no notification seam (R13).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkerPayoutServiceTest {

    private static final Long WORKER_ID = 500L;

    private static final LocalDate FROM = LocalDate.now().minusMonths(1);
    private static final LocalDate TO = LocalDate.now().minusDays(1);          // regular
    private static final LocalDate ADVANCE_TO = LocalDate.now().plusMonths(1); // advance

    @Mock
    private WorkerPayoutDao workerPayoutDao;
    @Mock
    private WorkerPayoutAllocationDao allocationDao;
    @Mock
    private UserDao userDao;
    @Mock
    private PayoutSuggestionService suggestionService;
    @Mock
    private WorkerPayoutServiceMapper serviceMapper;
    @Mock
    private WorkerPayoutDtoMapper dtoMapper;
    @Mock
    private AuditLogDao auditLogDao;
    @Mock
    private EntityManager entityManager;

    private final List<WorkerPayoutAllocationEntity> savedAllocations = new ArrayList<>();
    private WorkerPayoutService service;

    @BeforeEach
    void setUp() {
        service = new WorkerPayoutService(
                workerPayoutDao, allocationDao, userDao, suggestionService,
                new AllocationWriter(allocationDao, entityManager),
                new AdvanceCreditCalculator(), new AdvanceDeriver(),
                serviceMapper, dtoMapper, auditLogDao, entityManager);
        lenient().when(workerPayoutDao.save(any(WorkerPayoutEntity.class))).thenAnswer(inv -> {
            WorkerPayoutEntity p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(9000L);
            }
            return p;
        });
        lenient().when(allocationDao.save(any(WorkerPayoutAllocationEntity.class))).thenAnswer(inv -> {
            savedAllocations.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        lenient().when(dtoMapper.toDto(any(WorkerPayoutEntity.class))).thenReturn(sampleDto());
        lenient().when(workerPayoutDao.findByWorker(WORKER_ID)).thenReturn(List.of());
        stubWorker("WORKER");
    }

    // =======================================================================
    // create — distribution (R5.1–R5.6)
    // =======================================================================

    @Nested
    @DisplayName("create — worker-wide distribution (R5.1–R5.6)")
    class CreateDistribution {

        @Test
        @DisplayName("distributes amount + advanceApplied oldest-first; the last touched line is partial (R5.3, R5.4)")
        void distributesOldestFirstWithPartialLastLine() {
            // three lines (any projects) with remainders 100 / 50 / 80; prior advance credit 30.
            WorkReportRoomEntity a = line(10L, "0");
            WorkReportRoomEntity b = line(11L, "20");
            WorkReportRoomEntity c = line(12L, "0");
            when(suggestionService.payableLines(WORKER_ID, TO)).thenReturn(List.of(
                    new PayableCandidate(a, bd("100.00")),
                    new PayableCandidate(b, bd("50.00")),
                    new PayableCandidate(c, bd("80.00"))));
            WorkerPayoutEntity priorAdvance = advancePayout(bd("30.00"));
            when(workerPayoutDao.findByWorker(WORKER_ID)).thenReturn(List.of(priorAdvance));

            // override amount 140 ⇒ D = 140 + applied 30 = 170 ⇒ 100 / 50 / 20 (c partial).
            service.create(request(TO, bd("140"), null));

            WorkerPayoutEntity saved = capturedSave();
            assertThat(saved.getStatus()).isEqualTo(PayoutStatus.PLANNED);
            assertThat(saved.isAdvance()).isFalse();
            assertThat(saved.getAdvanceApplied()).isEqualByComparingTo("30");   // min(credit 30, gross 230)
            assertThat(saved.getSuggestedAmount()).isEqualByComparingTo("200"); // gross 230 − credit 30
            assertThat(saved.getAmount()).isEqualByComparingTo("140");

            assertThat(savedAllocations).extracting(x -> x.getWorkReportRoom().getId())
                    .containsExactly(10L, 11L, 12L);
            assertThat(savedAllocations).extracting(WorkerPayoutAllocationEntity::getAmount)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactly(bd("100"), bd("50"), bd("20"));
            assertThat(savedAllocations).allMatch(x -> x.getPayout() == saved);
            // paid_amount += allocation (R5.3, R5.9)
            assertThat(a.getPaidAmount()).isEqualByComparingTo("100");
            assertThat(b.getPaidAmount()).isEqualByComparingTo("70");
            assertThat(c.getPaidAmount()).isEqualByComparingTo("20");
        }

        @Test
        @DisplayName("defaults amount to the suggestion and payoutDate to today (R5.1, R5.2)")
        void defaultsAmountAndPayoutDate() {
            when(suggestionService.payableLines(WORKER_ID, TO)).thenReturn(List.of(
                    new PayableCandidate(line(10L, "0"), bd("75.50"))));

            service.create(request(TO, null, null));

            WorkerPayoutEntity saved = capturedSave();
            assertThat(saved.getAmount()).isEqualByComparingTo("75.50");
            assertThat(saved.getSuggestedAmount()).isEqualByComparingTo("75.50");
            assertThat(saved.getAdvanceApplied()).isEqualByComparingTo("0");
            assertThat(saved.getPayoutDate()).isEqualTo(LocalDate.now());
            assertThat(savedAllocations).hasSize(1);
        }

        @Test
        @DisplayName("an explicit payoutDate is stored as given (R5.1)")
        void explicitPayoutDateIsStored() {
            when(suggestionService.payableLines(WORKER_ID, TO)).thenReturn(List.of());
            LocalDate date = LocalDate.of(2026, 7, 15);

            service.create(request(TO, bd("10"), date));

            assertThat(capturedSave().getPayoutDate()).isEqualTo(date);
        }

        @Test
        @DisplayName("a surplus over the gross stays unallocated and is not turned into credit (R5.5)")
        void surplusStaysUnallocated() {
            WorkReportRoomEntity a = line(10L, "0");
            when(suggestionService.payableLines(WORKER_ID, TO)).thenReturn(List.of(
                    new PayableCandidate(a, bd("40.00"))));

            service.create(request(TO, bd("100"), null));

            WorkerPayoutEntity saved = capturedSave();
            assertThat(saved.getAmount()).isEqualByComparingTo("100");     // counts in full
            assertThat(saved.isAdvance()).isFalse();                       // not converted to credit
            assertThat(saved.getAdvanceApplied()).isEqualByComparingTo("0");
            assertThat(savedAllocations).hasSize(1);
            assertThat(a.getPaidAmount()).isEqualByComparingTo("40");
        }

        @Test
        @DisplayName("an advance uses the planned-basis suggestion, applies no credit, allocates nothing (R5.6)")
        void advanceAllocatesNothing() {
            when(suggestionService.suggest(eq(WORKER_ID), any(), any(), eq(true))).thenReturn(
                    new PayoutSuggestionDto(bd("400"), bd("0"), bd("0"), bd("400"), true, List.of()));

            service.create(request(ADVANCE_TO, null, null));

            WorkerPayoutEntity saved = capturedSave();
            assertThat(saved.isAdvance()).isTrue();
            assertThat(saved.getAmount()).isEqualByComparingTo("400");
            assertThat(saved.getAdvanceApplied()).isEqualByComparingTo("0");
            verify(suggestionService, never()).payableLines(anyLong(), any());
            verify(allocationDao, never()).save(any());
        }

        @Test
        @DisplayName("create writes one CREATE audit row")
        void createIsAudited() {
            when(suggestionService.payableLines(WORKER_ID, TO)).thenReturn(List.of());
            service.create(request(TO, bd("1"), null));
            verify(auditLogDao, times(1)).save(any());
        }
    }

    // =======================================================================
    // create — validation (R5.7)
    // =======================================================================

    @Nested
    @DisplayName("create — validation order and codes (R5.7)")
    class CreateValidation {

        @Test
        @DisplayName("invalid period → 422 period.invalid, checked before the channel")
        void invalidPeriodFirst() {
            assertApiError(() -> service.create(new CreatePayoutRequest(
                            WORKER_ID, null, TO, FROM, null, bd("-1"), null)),
                    HttpStatus.UNPROCESSABLE_ENTITY, "error.workerPayout.period.invalid");
            verify(workerPayoutDao, never()).save(any());
        }

        @Test
        @DisplayName("missing channel → 422 channel.required, checked before the amount")
        void missingChannelBeforeAmount() {
            assertApiError(() -> service.create(new CreatePayoutRequest(
                            WORKER_ID, null, FROM, TO, null, bd("-1"), null)),
                    HttpStatus.UNPROCESSABLE_ENTITY, "error.workerPayout.channel.required");
        }

        @Test
        @DisplayName("negative amount → 422 amount.invalid, checked before the worker")
        void negativeAmountBeforeWorker() {
            stubWorker("FOREMAN");
            assertApiError(() -> service.create(new CreatePayoutRequest(
                            WORKER_ID, null, FROM, TO, PaymentChannel.CASH, bd("-1"), null)),
                    HttpStatus.UNPROCESSABLE_ENTITY, "error.workerPayout.amount.invalid");
        }

        @Test
        @DisplayName("a non-WORKER user → 422 worker.invalid")
        void nonWorkerRejected() {
            stubWorker("FOREMAN");
            assertApiError(() -> service.create(request(TO, bd("1"), null)),
                    HttpStatus.UNPROCESSABLE_ENTITY, "error.workerPayout.worker.invalid");
            verify(workerPayoutDao, never()).save(any());
        }

        @Test
        @DisplayName("an unknown user → 422 worker.invalid")
        void unknownWorkerRejected() {
            when(userDao.findById(WORKER_ID)).thenReturn(Optional.empty());
            assertApiError(() -> service.create(request(TO, bd("1"), null)),
                    HttpStatus.UNPROCESSABLE_ENTITY, "error.workerPayout.worker.invalid");
        }
    }

    // =======================================================================
    // suggest (R4.1, R4.8)
    // =======================================================================

    @Nested
    @DisplayName("suggest — worker-wide (R4.1, R4.8)")
    class Suggest {

        @Test
        @DisplayName("delegates without a project; a past period is regular, a future one an advance")
        void delegatesWorkerWide() {
            service.suggest(WORKER_ID, FROM, TO);
            service.suggest(WORKER_ID, FROM, ADVANCE_TO);
            verify(suggestionService).suggest(WORKER_ID, FROM, TO, false);
            verify(suggestionService).suggest(WORKER_ID, FROM, ADVANCE_TO, true);
        }

        @Test
        @DisplayName("rejects to < from and a non-WORKER with 422")
        void rejectsInvalidInput() {
            assertApiError(() -> service.suggest(WORKER_ID, TO, FROM),
                    HttpStatus.UNPROCESSABLE_ENTITY, "error.workerPayout.period.invalid");
            stubWorker("CLIENT");
            assertApiError(() -> service.suggest(WORKER_ID, FROM, TO),
                    HttpStatus.UNPROCESSABLE_ENTITY, "error.workerPayout.worker.invalid");
        }
    }

    // =======================================================================
    // lifecycle (R6.1–R6.4)
    // =======================================================================

    @Nested
    @DisplayName("lifecycle — markPaid / delete (R6.1–R6.4)")
    class Lifecycle {

        @Test
        @DisplayName("mark-paid moves PLANNED → PAID and sets paidAt (R6.1)")
        void markPaid() {
            WorkerPayoutEntity p = persisted(PayoutStatus.PLANNED, false);
            service.markPaid(p.getId());
            assertThat(p.getStatus()).isEqualTo(PayoutStatus.PAID);
            assertThat(p.getPaidAt()).isNotNull();
            verify(auditLogDao).save(any());
        }

        @Test
        @DisplayName("mark-paid / delete on a PAID payout → 409 illegalTransition (R6.1)")
        void paidIsTerminal() {
            WorkerPayoutEntity p = persisted(PayoutStatus.PAID, false);
            assertApiError(() -> service.markPaid(p.getId()),
                    HttpStatus.CONFLICT, "error.workerPayout.status.illegalTransition");
            assertApiError(() -> service.delete(p.getId()),
                    HttpStatus.CONFLICT, "error.workerPayout.status.illegalTransition");
            verify(workerPayoutDao, never()).deleteById(any());
            verify(allocationDao, never()).findByPayoutId(anyLong());
        }

        @Test
        @DisplayName("unknown id → 404")
        void unknownId() {
            when(workerPayoutDao.findById(1L)).thenReturn(Optional.empty());
            assertApiError(() -> service.markPaid(1L), HttpStatus.NOT_FOUND, "error.entity.not.found");
        }

        @Test
        @DisplayName("deleting a PLANNED regular payout releases its allocations: paid_amount −= a (R6.2, R6.4)")
        void deleteRegularReleasesAllocations() {
            WorkerPayoutEntity p = persisted(PayoutStatus.PLANNED, false);
            WorkReportRoomEntity a = line(10L, "100");
            WorkReportRoomEntity b = line(11L, "35");
            List<WorkerPayoutAllocationEntity> rows = List.of(allocation(p, a, "100"), allocation(p, b, "20"));
            when(allocationDao.findByPayoutId(p.getId())).thenReturn(rows);

            service.delete(p.getId());

            assertThat(a.getPaidAmount()).isEqualByComparingTo("0");
            assertThat(b.getPaidAmount()).isEqualByComparingTo("15");
            verify(allocationDao).deleteAll(rows);
            verify(workerPayoutDao).deleteById(p.getId());
            verify(auditLogDao).save(any()); // DELETE audit
        }

        @Test
        @DisplayName("deleting an advance whose credit regular payouts consumed → 409 alreadyApplied (R6.3)")
        void deleteConsumedAdvanceRejected() {
            WorkerPayoutEntity advance = persisted(PayoutStatus.PLANNED, true);
            advance.setAmount(bd("100"));
            WorkerPayoutEntity regular = persisted(PayoutStatus.PAID, false);
            regular.setAdvanceApplied(bd("60"));
            WorkerPayoutEntity otherAdvance = persisted(PayoutStatus.PAID, true);
            otherAdvance.setAmount(bd("50"));
            when(workerPayoutDao.findByWorker(WORKER_ID)).thenReturn(List.of(advance, regular, otherAdvance));

            assertApiError(() -> service.delete(advance.getId()),
                    HttpStatus.CONFLICT, "error.workerPayout.advance.alreadyApplied");
            verify(workerPayoutDao, never()).deleteById(any());
        }

        @Test
        @DisplayName("deleting an advance whose credit is still covered by other advances succeeds (R6.3)")
        void deleteCoveredAdvanceSucceeds() {
            WorkerPayoutEntity advance = persisted(PayoutStatus.PLANNED, true);
            advance.setAmount(bd("100"));
            WorkerPayoutEntity regular = persisted(PayoutStatus.PAID, false);
            regular.setAdvanceApplied(bd("50"));
            WorkerPayoutEntity otherAdvance = persisted(PayoutStatus.PAID, true);
            otherAdvance.setAmount(bd("50"));
            when(workerPayoutDao.findByWorker(WORKER_ID)).thenReturn(List.of(advance, regular, otherAdvance));

            service.delete(advance.getId());

            verify(workerPayoutDao).deleteById(advance.getId());
            verify(allocationDao, never()).findByPayoutId(anyLong()); // an advance has no allocations
        }
    }

    // =======================================================================
    // reads + structure (R14.2, R13)
    // =======================================================================

    @Nested
    @DisplayName("reads and structure (R14.2, R13)")
    class Reads {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("list queries the company-wide payouts newest first and maps them")
        void listMapsPayouts() {
            List<WorkerPayoutEntity> rows = List.of(persisted(PayoutStatus.PLANNED, false));
            when(workerPayoutDao.findAll(any(Specification.class), any(Sort.class))).thenReturn(rows);
            when(dtoMapper.toDtos(rows)).thenReturn(List.of(sampleDto()));

            List<WorkerPayoutDto> result = service.list(new PayoutFilter(WORKER_ID, FROM, TO, PayoutStatus.PLANNED));

            assertThat(result).hasSize(1);
            ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
            verify(workerPayoutDao).findAll(any(Specification.class), sort.capture());
            assertThat(sort.getValue().getOrderFor("payoutDate").isDescending()).isTrue();
        }

        @Test
        @DisplayName("allocations lists each line with project, work item, room, and amount")
        void allocationsListed() {
            WorkerPayoutEntity p = persisted(PayoutStatus.PLANNED, false);
            WorkReportRoomEntity l = line(10L, "40");
            ProjectEntity project = new ProjectEntity();
            project.setId(7L);
            project.setName("Project A");
            WorkItemEntity item = new WorkItemEntity();
            item.setCode("WI");
            item.setNamePL("Malowanie");
            WorkReportEntity report = new WorkReportEntity();
            report.setProject(project);
            report.setWorkItem(item);
            l.setReport(report);
            RoomEntity room = new RoomEntity();
            room.setLabel("Kuchnia");
            l.setRoom(room);
            when(allocationDao.findByPayoutId(p.getId())).thenReturn(List.of(allocation(p, l, "40")));

            List<PayoutAllocationDto> result = service.allocations(p.getId());

            assertThat(result).singleElement().satisfies(d -> {
                assertThat(d.lineId()).isEqualTo(10L);
                assertThat(d.projectId()).isEqualTo(7L);
                assertThat(d.projectName()).isEqualTo("Project A");
                assertThat(d.workItemName()).isEqualTo("Malowanie");
                assertThat(d.roomName()).isEqualTo("Kuchnia");
                assertThat(d.amount()).isEqualByComparingTo("40");
            });
        }

        @Test
        @DisplayName("the service is not project-scoped and has no notification seam (R14.2, R13)")
        void structure() {
            assertThat(ProjectScopedService.class.isAssignableFrom(WorkerPayoutService.class)).isFalse();
            boolean hasNotificationSeam = java.util.Arrays
                    .stream(WorkerPayoutService.class.getDeclaredConstructors())
                    .flatMap(c -> java.util.Arrays.stream(c.getParameterTypes()))
                    .map(Class::getName)
                    .anyMatch(n -> n.contains("Notification") || n.contains("ApplicationEventPublisher"));
            assertThat(hasNotificationSeam).isFalse();
        }
    }

    // =======================================================================
    // helpers
    // =======================================================================

    private void stubWorker(String roleCode) {
        UserEntity user = new UserEntity();
        user.setId(WORKER_ID);
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        user.setRole(role);
        lenient().when(userDao.findById(WORKER_ID)).thenReturn(Optional.of(user));
    }

    private static CreatePayoutRequest request(LocalDate to, BigDecimal amount, LocalDate payoutDate) {
        return new CreatePayoutRequest(WORKER_ID, payoutDate, FROM, to, PaymentChannel.BANK_TRANSFER, amount, null);
    }

    private WorkerPayoutEntity capturedSave() {
        ArgumentCaptor<WorkerPayoutEntity> captor = ArgumentCaptor.forClass(WorkerPayoutEntity.class);
        verify(workerPayoutDao).save(captor.capture());
        return captor.getValue();
    }

    private long idSeq = 1000L;

    private WorkerPayoutEntity persisted(PayoutStatus status, boolean advance) {
        WorkerPayoutEntity p = new WorkerPayoutEntity();
        p.setId(++idSeq);
        UserEntity worker = new UserEntity();
        worker.setId(WORKER_ID);
        p.setWorker(worker);
        p.setPayoutDate(FROM);
        p.setStatus(status);
        p.setAdvance(advance);
        p.setPeriodFrom(FROM);
        p.setPeriodTo(advance ? ADVANCE_TO : TO);
        p.setAmount(bd("100"));
        p.setSuggestedAmount(bd("100"));
        p.setAdvanceApplied(BigDecimal.ZERO);
        p.setPaymentChannel(PaymentChannel.CASH);
        lenient().when(workerPayoutDao.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    private WorkerPayoutEntity advancePayout(BigDecimal amount) {
        WorkerPayoutEntity p = persisted(PayoutStatus.PAID, true);
        p.setAmount(amount);
        return p;
    }

    private static WorkReportRoomEntity line(Long id, String paid) {
        WorkReportRoomEntity l = new WorkReportRoomEntity();
        l.setId(id);
        l.setPaidAmount(bd(paid));
        return l;
    }

    private static WorkerPayoutAllocationEntity allocation(WorkerPayoutEntity p, WorkReportRoomEntity l, String amount) {
        WorkerPayoutAllocationEntity a = new WorkerPayoutAllocationEntity();
        a.setPayout(p);
        a.setWorkReportRoom(l);
        a.setAmount(bd(amount));
        return a;
    }

    private static WorkerPayoutDto sampleDto() {
        return new WorkerPayoutDto(1L, WORKER_ID, "Worker", LocalDate.now(), FROM, TO, bd("100"), bd("100"),
                BigDecimal.ZERO, PaymentChannel.CASH, PayoutStatus.PLANNED, false, null, null);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static void assertApiError(Runnable action, HttpStatus status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(status.value());
                    assertThat(api.getMessageCode()).isEqualTo(code);
                });
    }
}
