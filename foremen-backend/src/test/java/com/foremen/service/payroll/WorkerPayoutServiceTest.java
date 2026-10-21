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
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.AdminDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.WorkReportRoomDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.WorkerPayoutServiceMapper;
import com.foremen.service.payroll.dto.CreatePayoutRequest;
import com.foremen.service.payroll.dto.PayoutLineDto;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.payroll.dto.WorkerPayoutDto;
import com.foremen.service.payroll.dto.mapper.WorkerPayoutDtoMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-11 task 8.1 — plain Mockito unit tests for {@link WorkerPayoutService}, the money-OUT
 * payout lifecycle service (design §WorkerPayoutService). The DAOs, pure collaborators (stubbed where
 * their arithmetic is exercised elsewhere), mapper, and {@link EntityManager} are mocked; the caller
 * user id / role authority is driven through {@link SecurityContextHolder}, mirroring
 * {@code WorkReportServiceTest}.
 *
 * <p>Covers the unit cases of the design §Testing Strategy:
 * <ul>
 *   <li><b>create happy paths</b> — a regular payout links the covered lines, applies the credit, and
 *       is persisted {@code PLANNED} (R4.2); an advance adds credit, applies none, and links no line
 *       (R5.5);</li>
 *   <li><b>error edges</b> — missing channel (R2.2), negative amount (R4.4), invalid period (R4.5),
 *       non-member worker (R4.6), an illegal transition on a {@code PAID} payout (R7.2), and an
 *       advance-already-applied delete (R5.7);</li>
 *   <li><b>no-notification assertion</b> — {@link WorkerPayoutService} declares <b>no</b> notification
 *       collaborator at all, and create / pay / delete complete without one (R9.1).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkerPayoutServiceTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long WORKER_ID = 500L;
    private static final Long ADMIN_ID = 1L;

    private static final LocalDate FROM = LocalDate.now().minusMonths(1);
    private static final LocalDate TO = LocalDate.now().minusDays(1); // past -> regular (non-advance)
    private static final LocalDate ADVANCE_TO = LocalDate.now().plusMonths(1); // future -> advance

    @Mock
    private WorkerPayoutDao workerPayoutDao;
    @Mock
    private WorkReportRoomDao workReportRoomDao;
    @Mock
    private ProjectMemberDao projectMemberDao;
    @Mock
    private ProjectAccessCache projectAccessCache;
    @Mock
    private PayoutSuggestionService suggestionService;
    @Mock
    private ReportLineLinker reportLineLinker;
    @Mock
    private WorkerPayoutServiceMapper serviceMapper;
    @Mock
    private WorkerPayoutDtoMapper dtoMapper;
    @Mock
    private AuditLogDao auditLogDao;
    @Mock
    private EntityManager entityManager;

    // The advance-credit arithmetic is pure (covered by AdvanceCreditCalculatorPropertyTest); the
    // service composes the REAL calculator so the credit figures are the production ones.
    private final AdvanceCreditCalculator advanceCreditCalculator = new AdvanceCreditCalculator();
    private final AdvanceDeriver advanceDeriver = new AdvanceDeriver();

    private WorkerPayoutService service;

    @BeforeEach
    void setUp() {
        service = new WorkerPayoutService(
                workerPayoutDao, workReportRoomDao, projectMemberDao, projectAccessCache,
                suggestionService, reportLineLinker, advanceCreditCalculator, advanceDeriver,
                serviceMapper, dtoMapper, auditLogDao, entityManager);
        // save() echoes its argument so the committed entity is the one under test.
        lenient().when(workerPayoutDao.save(any(WorkerPayoutEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // getReference(type, id) returns a lightweight stub carrying only its id.
        lenient().when(entityManager.getReference(any(), any()))
                .thenAnswer(inv -> referenceStub(inv.getArgument(0), (Long) inv.getArgument(1)));
        // The read mapping is not under test; echo a trivial dto so create()/markPaid() return non-null.
        lenient().when(dtoMapper.toDto(any(WorkerPayoutEntity.class)))
                .thenReturn(sampleDto());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // =======================================================================
    // create — happy paths (R4.2, R5.5)
    // =======================================================================

    @Nested
    @DisplayName("create — happy paths (R4.2, R5.5)")
    class CreateHappyPaths {

        @Test
        @DisplayName("a regular payout links the covered lines, applies the credit, and is PLANNED (R4.2)")
        void regularPayoutLinksLinesAppliesCreditAndIsPlanned() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            stubActiveMember();
            // gross 300, remaining credit 100 -> appliedCredit 100, suggested 200.
            stubSuggest(false, bd("300"), bd("100"), bd("100"), bd("200"));
            List<WorkReportRoomEntity> lines = List.of(line(10L), line(11L));
            when(suggestionService.linkableLines(PROJECT_ID, WORKER_ID, FROM, TO)).thenReturn(lines);

            service.create(PROJECT_ID, new CreatePayoutRequest(
                    WORKER_ID, FROM, TO, PaymentChannel.BANK_TRANSFER, null, "ok"));

            WorkerPayoutEntity saved = capturedSave();
            assertThat(saved.getStatus()).isEqualTo(PayoutStatus.PLANNED);
            assertThat(saved.isAdvance()).isFalse();
            assertThat(saved.getSuggestedAmount()).isEqualByComparingTo("200"); // snapshot (R1.4)
            assertThat(saved.getAmount()).isEqualByComparingTo("200");          // defaults to suggested
            assertThat(saved.getAdvanceApplied()).isEqualByComparingTo("100");  // min(remaining, gross)
            assertThat(saved.getPaymentChannel()).isEqualTo(PaymentChannel.BANK_TRANSFER);
            // the covered unpaid lines are linked in-transaction (R4.3, R6.1).
            verify(reportLineLinker).link(eq(saved), eq(lines));
            verify(reportLineLinker, never()).release(anyLong());
        }

        @Test
        @DisplayName("an advance adds credit, applies none, and links no line (R5.5)")
        void advancePayoutAddsCreditAndLinksNoLine() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            stubActiveMember();
            // advance: planned-basis gross 400, no credit deduction (appliedCredit 0).
            stubSuggest(true, bd("400"), BigDecimal.ZERO, BigDecimal.ZERO, bd("400"));

            service.create(PROJECT_ID, new CreatePayoutRequest(
                    WORKER_ID, FROM, ADVANCE_TO, PaymentChannel.CASH, null, null));

            WorkerPayoutEntity saved = capturedSave();
            assertThat(saved.getStatus()).isEqualTo(PayoutStatus.PLANNED);
            assertThat(saved.isAdvance()).isTrue();
            assertThat(saved.getAdvanceApplied()).isEqualByComparingTo("0"); // an advance applies no credit
            assertThat(saved.getAmount()).isEqualByComparingTo("400");       // defaults to planned-basis suggestion
            // an advance links NO report line (R6.4) and never resolves a linkable set.
            verify(reportLineLinker, never()).link(any(), any());
            verify(suggestionService, never()).linkableLines(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("an overridden cash amount does not change the linked set or advanceApplied (R4.4)")
        void overrideAmountDoesNotChangeLinkageOrCredit() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            stubActiveMember();
            stubSuggest(false, bd("300"), bd("100"), bd("100"), bd("200"));
            List<WorkReportRoomEntity> lines = List.of(line(10L));
            when(suggestionService.linkableLines(PROJECT_ID, WORKER_ID, FROM, TO)).thenReturn(lines);

            // override the cash amount to 999 (independent of linkage / credit).
            service.create(PROJECT_ID, new CreatePayoutRequest(
                    WORKER_ID, FROM, TO, PaymentChannel.CASH, bd("999"), null));

            WorkerPayoutEntity saved = capturedSave();
            assertThat(saved.getAmount()).isEqualByComparingTo("999");          // override honored
            assertThat(saved.getSuggestedAmount()).isEqualByComparingTo("200"); // snapshot unchanged
            assertThat(saved.getAdvanceApplied()).isEqualByComparingTo("100");  // follows gross, not override
            verify(reportLineLinker).link(eq(saved), eq(lines));               // linked set follows gross
        }
    }

    // =======================================================================
    // create — error edges (R2.2, R4.4, R4.5, R4.6)
    // =======================================================================

    @Nested
    @DisplayName("create — error edges (R2.2, R4.4, R4.5, R4.6)")
    class CreateErrorEdges {

        @Test
        @DisplayName("a missing channel is rejected 422 error.workerPayout.channel.required (R2.2)")
        void missingChannelRejected() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            stubActiveMember();

            assertApiError(() -> service.create(PROJECT_ID, new CreatePayoutRequest(
                            WORKER_ID, FROM, TO, null, bd("10"), null)),
                    HttpStatus.UNPROCESSABLE_ENTITY, "error.workerPayout.channel.required");
            verify(workerPayoutDao, never()).save(any());
        }

        @Test
        @DisplayName("a negative amount is rejected 422 error.workerPayout.amount.invalid (R4.4)")
        void negativeAmountRejected() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            stubActiveMember();

            assertApiError(() -> service.create(PROJECT_ID, new CreatePayoutRequest(
                            WORKER_ID, FROM, TO, PaymentChannel.CASH, bd("-1"), null)),
                    HttpStatus.UNPROCESSABLE_ENTITY, "error.workerPayout.amount.invalid");
            verify(workerPayoutDao, never()).save(any());
        }

        @Test
        @DisplayName("an invalid period (to < from) is rejected 422 error.workerPayout.period.invalid (R4.5)")
        void invalidPeriodRejected() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");

            assertApiError(() -> service.create(PROJECT_ID, new CreatePayoutRequest(
                            WORKER_ID, TO, FROM, PaymentChannel.CASH, bd("10"), null)),
                    HttpStatus.UNPROCESSABLE_ENTITY, "error.workerPayout.period.invalid");
            verify(workerPayoutDao, never()).save(any());
        }

        @Test
        @DisplayName("a non-ACTIVE-member worker is rejected 422 error.workerPayout.worker.notMember (R4.6)")
        void nonMemberWorkerRejected() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            // No membership row -> not a member.
            when(projectMemberDao.findByUserIdAndProjectId(WORKER_ID, PROJECT_ID))
                    .thenReturn(Optional.empty());

            assertApiError(() -> service.create(PROJECT_ID, new CreatePayoutRequest(
                            WORKER_ID, FROM, TO, PaymentChannel.CASH, bd("10"), null)),
                    HttpStatus.UNPROCESSABLE_ENTITY, "error.workerPayout.worker.notMember");
            verify(workerPayoutDao, never()).save(any());
        }

        @Test
        @DisplayName("an INACTIVE member is also rejected error.workerPayout.worker.notMember (R4.6)")
        void inactiveMemberRejected() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            ProjectMemberEntity member = new ProjectMemberEntity();
            member.setAssignmentStatus(AssignmentStatus.INACTIVE);
            when(projectMemberDao.findByUserIdAndProjectId(WORKER_ID, PROJECT_ID))
                    .thenReturn(Optional.of(member));

            assertApiError(() -> service.create(PROJECT_ID, new CreatePayoutRequest(
                            WORKER_ID, FROM, TO, PaymentChannel.CASH, bd("10"), null)),
                    HttpStatus.UNPROCESSABLE_ENTITY, "error.workerPayout.worker.notMember");
            verify(workerPayoutDao, never()).save(any());
        }
    }

    // =======================================================================
    // markPaid / delete — lifecycle (R7.1, R7.2, R5.7)
    // =======================================================================

    @Nested
    @DisplayName("lifecycle — markPaid / delete (R7.1, R7.2, R5.7)")
    class Lifecycle {

        @Test
        @DisplayName("marking a PLANNED payout paid sets PAID + paidAt (R7.1)")
        void markPaidTransitionsPlannedToPaid() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            WorkerPayoutEntity payout = persisted(PayoutStatus.PLANNED, false);
            when(workerPayoutDao.findById(payout.getId())).thenReturn(Optional.of(payout));

            service.markPaid(payout.getId());

            assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAID);
            assertThat(payout.getPaidAt()).isNotNull();
        }

        @Test
        @DisplayName("marking an already-PAID payout paid is refused 409 illegalTransition (R7.2)")
        void markPaidOnPaidIsIllegal() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            WorkerPayoutEntity payout = persisted(PayoutStatus.PAID, false);
            when(workerPayoutDao.findById(payout.getId())).thenReturn(Optional.of(payout));

            assertApiError(() -> service.markPaid(payout.getId()),
                    HttpStatus.CONFLICT, "error.workerPayout.status.illegalTransition");
            assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAID); // unchanged
        }

        @Test
        @DisplayName("deleting a PAID payout is refused 409 illegalTransition (R7.2)")
        void deletePaidIsIllegal() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            WorkerPayoutEntity payout = persisted(PayoutStatus.PAID, false);
            when(workerPayoutDao.findById(payout.getId())).thenReturn(Optional.of(payout));

            assertApiError(() -> service.delete(payout.getId()),
                    HttpStatus.CONFLICT, "error.workerPayout.status.illegalTransition");
            verify(workerPayoutDao, never()).deleteById(any());
            verify(reportLineLinker, never()).release(anyLong());
        }

        @Test
        @DisplayName("deleting a PLANNED regular payout releases its linked lines (R6.3, R7.3)")
        void deletePlannedRegularReleasesLines() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            WorkerPayoutEntity payout = persisted(PayoutStatus.PLANNED, false);
            when(workerPayoutDao.findById(payout.getId())).thenReturn(Optional.of(payout));

            service.delete(payout.getId());

            verify(reportLineLinker).release(payout.getId());
            verify(workerPayoutDao).deleteById(payout.getId());
        }

        @Test
        @DisplayName("deleting an advance whose credit a later regular payout consumed is refused (R5.7)")
        void deleteAdvanceAlreadyAppliedIsRefused() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            WorkerPayoutEntity advance = persisted(PayoutStatus.PLANNED, true);
            advance.setAmount(bd("100"));
            when(workerPayoutDao.findById(advance.getId())).thenReturn(Optional.of(advance));
            // The worker's OTHER payouts: a regular that already applied 100 of credit, but the OTHER
            // advances only total 0 -> removing THIS advance would leave the applied offset uncovered.
            WorkerPayoutEntity regular = persisted(PayoutStatus.PLANNED, false);
            regular.setAdvanceApplied(bd("100"));
            when(workerPayoutDao.findByProjectAndWorker(PROJECT_ID, WORKER_ID))
                    .thenReturn(List.of(advance, regular));

            assertApiError(() -> service.delete(advance.getId()),
                    HttpStatus.CONFLICT, "error.workerPayout.advance.alreadyApplied");
            verify(workerPayoutDao, never()).deleteById(any());
            verify(reportLineLinker, never()).release(anyLong());
        }

        @Test
        @DisplayName("deleting an advance whose credit is NOT yet consumed removes it (R5.7)")
        void deleteAdvanceNotConsumedSucceeds() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            WorkerPayoutEntity advance = persisted(PayoutStatus.PLANNED, true);
            advance.setAmount(bd("100"));
            when(workerPayoutDao.findById(advance.getId())).thenReturn(Optional.of(advance));
            // No regular payout has drawn any credit -> safe to delete.
            when(workerPayoutDao.findByProjectAndWorker(PROJECT_ID, WORKER_ID))
                    .thenReturn(List.of(advance));

            service.delete(advance.getId());

            verify(workerPayoutDao).deleteById(advance.getId());
            // an advance linked no line, so release is never invoked for it (R6.4).
            verify(reportLineLinker, never()).release(anyLong());
        }
    }

    // =======================================================================
    // no-notification assertion (R9.1)
    // =======================================================================

    @Nested
    @DisplayName("no notifications — create / pay / delete emit none (R9.1)")
    class NoNotifications {

        @Test
        @DisplayName("WorkerPayoutService declares no notification collaborator at all (R9.1)")
        void serviceHasNoNotificationDependency() {
            // Structural guarantee: no constructor parameter type references a notification / event
            // publisher. Since the service literally has no such seam, create/pay/delete cannot emit.
            boolean hasNotificationSeam = java.util.Arrays
                    .stream(WorkerPayoutService.class.getDeclaredConstructors())
                    .flatMap(c -> java.util.Arrays.stream(c.getParameterTypes()))
                    .map(Class::getName)
                    .anyMatch(n -> n.contains("Notification") || n.contains("ApplicationEventPublisher"));
            assertThat(hasNotificationSeam)
                    .as("WorkerPayoutService has no NotificationService / event-publisher dependency (R9.1)")
                    .isFalse();
        }

        @Test
        @DisplayName("a full create -> pay -> delete cycle completes with no notification seam (R9.1)")
        void createPayDeleteCycleEmitsNothing() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            stubActiveMember();
            stubSuggest(false, bd("100"), BigDecimal.ZERO, BigDecimal.ZERO, bd("100"));
            when(suggestionService.linkableLines(PROJECT_ID, WORKER_ID, FROM, TO))
                    .thenReturn(List.of(line(10L)));

            service.create(PROJECT_ID, new CreatePayoutRequest(
                    WORKER_ID, FROM, TO, PaymentChannel.CASH, null, null));

            WorkerPayoutEntity payout = persisted(PayoutStatus.PLANNED, false);
            when(workerPayoutDao.findById(payout.getId())).thenReturn(Optional.of(payout));
            service.markPaid(payout.getId());

            WorkerPayoutEntity toDelete = persisted(PayoutStatus.PLANNED, false);
            when(workerPayoutDao.findById(toDelete.getId())).thenReturn(Optional.of(toDelete));
            when(workerPayoutDao.findByProjectAndWorker(PROJECT_ID, WORKER_ID))
                    .thenReturn(List.of(toDelete));
            service.delete(toDelete.getId());

            // Only the expected collaborators were touched — the audit log and the FK linker — never a
            // notification. (The suite has no notification mock because the service has no such field.)
            verify(auditLogDao, times(3)).save(any()); // one per create / pay / delete (R12.1)
        }
    }

    // =======================================================================
    // helpers
    // =======================================================================

    private void stubActiveMember() {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setAssignmentStatus(AssignmentStatus.ACTIVE);
        lenient().when(projectMemberDao.findByUserIdAndProjectId(WORKER_ID, PROJECT_ID))
                .thenReturn(Optional.of(member));
    }

    private void stubSuggest(boolean advance, BigDecimal gross, BigDecimal remaining,
                             BigDecimal applied, BigDecimal suggested) {
        PayoutSuggestionDto dto = new PayoutSuggestionDto(
                gross, remaining, applied, suggested, advance, List.of());
        lenient().when(suggestionService.suggest(eq(PROJECT_ID), eq(WORKER_ID), any(), any(), eq(advance)))
                .thenReturn(dto);
    }

    private WorkerPayoutEntity capturedSave() {
        ArgumentCaptor<WorkerPayoutEntity> captor = ArgumentCaptor.forClass(WorkerPayoutEntity.class);
        verify(workerPayoutDao).save(captor.capture());
        return captor.getValue();
    }

    private WorkerPayoutEntity persisted(PayoutStatus status, boolean advance) {
        WorkerPayoutEntity p = new WorkerPayoutEntity();
        p.setId(nextId());
        p.setProject(referenceStub(com.foremen.dao.model.ProjectEntity.class, PROJECT_ID));
        p.setWorker(referenceStub(UserEntity.class, WORKER_ID));
        p.setStatus(status);
        p.setAdvance(advance);
        p.setPeriodFrom(FROM);
        p.setPeriodTo(advance ? ADVANCE_TO : TO);
        p.setAmount(bd("100"));
        p.setSuggestedAmount(bd("100"));
        p.setAdvanceApplied(advance ? BigDecimal.ZERO : bd("0"));
        p.setPaymentChannel(PaymentChannel.CASH);
        return p;
    }

    private static WorkReportRoomEntity line(Long id) {
        WorkReportRoomEntity l = new WorkReportRoomEntity();
        l.setId(id);
        return l;
    }

    private static WorkerPayoutDto sampleDto() {
        return new WorkerPayoutDto(1L, WORKER_ID, "Worker", FROM, TO, bd("100"), bd("100"),
                BigDecimal.ZERO, PaymentChannel.CASH, PayoutStatus.PLANNED, false, null, null);
    }

    private long idSeq = 1000L;

    private long nextId() {
        return ++idSeq;
    }

    @SuppressWarnings("unchecked")
    private static <T> T referenceStub(Class<?> type, Long id) {
        try {
            Object stub = type.getDeclaredConstructor().newInstance();
            type.getMethod("setId", Long.class).invoke(stub, id);
            return (T) stub;
        } catch (Exception e) {
            throw new IllegalStateException("cannot build reference stub for " + type, e);
        }
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static void authenticate(Long userId, String authority) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "pw", List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(token);
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
