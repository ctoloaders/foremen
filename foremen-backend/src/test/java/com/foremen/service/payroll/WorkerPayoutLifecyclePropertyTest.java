package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.WorkReportRoomDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.WorkerPayoutServiceMapper;
import com.foremen.service.payroll.dto.CreatePayoutRequest;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.payroll.dto.WorkerPayoutDto;
import com.foremen.service.payroll.dto.mapper.WorkerPayoutDtoMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-11 task 8.1 — <b>Property 7: SuggestedSnapshotImmutable</b> and <b>Property 8: Lifecycle</b>
 * (design §Correctness Properties).
 *
 * <p><b>Property 7</b> — for any payout, {@code suggestedAmount} equals the value computed at creation
 * and is never changed by a later operation; {@code amount} defaults to {@code suggestedAmount} when
 * the issuer does not override it and may be any value {@code ≥ 0} otherwise (R1.4, R4.2).
 *
 * <p><b>Property 8</b> — for any payout the only permitted transition is {@code PLANNED → PAID} (and a
 * {@code PLANNED → (deleted)} hard-delete); a {@code PAID} payout is terminal: every mark-paid /
 * delete on it is rejected with {@code error.workerPayout.status.illegalTransition}, changing nothing
 * (R7.1, R7.2, R7.3).
 *
 * <p><b>Validates: Requirements 1.4, 4.2, 7.1, 7.2, 7.3</b>
 */
@Tag("Feature: FOR-06-11-payroll, Property 7 + 8: suggested snapshot immutability + PLANNED→PAID lifecycle, PAID terminal")
class WorkerPayoutLifecyclePropertyTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long WORKER_ID = 500L;
    private static final LocalDate FROM = LocalDate.now().minusMonths(1);
    private static final LocalDate REGULAR_TO = LocalDate.now().minusDays(1);

    // =============================================================================================
    // Property 7 — suggestedAmount is a creation snapshot; amount defaults to it, or any ≥ 0 override.
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 7: amount defaults to the suggested snapshot when no override is supplied")
    void amountDefaultsToSuggestedSnapshotWhenNotOverridden(@ForAll("money") BigDecimal suggested) {
        Fixture f = new Fixture();
        f.stubSuggest(false, suggested, BigDecimal.ZERO, suggested);
        when(f.suggestionService.linkableLines(PROJECT_ID, WORKER_ID, FROM, REGULAR_TO))
                .thenReturn(List.of());

        f.service.create(PROJECT_ID, new CreatePayoutRequest(
                WORKER_ID, FROM, REGULAR_TO, PaymentChannel.CASH, /* amount */ null, null));

        WorkerPayoutEntity saved = f.capturedSave();
        assertThat(saved.getSuggestedAmount())
                .as("suggestedAmount is the snapshot of the computed suggestion (R1.4)")
                .isEqualByComparingTo(suggested);
        assertThat(saved.getAmount())
                .as("amount defaults to suggestedAmount when the issuer does not override (R4.2)")
                .isEqualByComparingTo(suggested);
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 7: a ≥0 override sets amount while suggestedAmount stays the snapshot")
    void overrideSetsAmountButSnapshotIsUnchanged(
            @ForAll("money") BigDecimal suggested,
            @ForAll("money") BigDecimal override) {

        Fixture f = new Fixture();
        f.stubSuggest(false, suggested, BigDecimal.ZERO, suggested);
        when(f.suggestionService.linkableLines(PROJECT_ID, WORKER_ID, FROM, REGULAR_TO))
                .thenReturn(List.of());

        f.service.create(PROJECT_ID, new CreatePayoutRequest(
                WORKER_ID, FROM, REGULAR_TO, PaymentChannel.CASH, override, null));

        WorkerPayoutEntity saved = f.capturedSave();
        assertThat(saved.getSuggestedAmount())
                .as("the suggested snapshot is independent of the override (R1.4)")
                .isEqualByComparingTo(suggested);
        assertThat(saved.getAmount())
                .as("any ≥ 0 override becomes the cash amount (R4.2)")
                .isEqualByComparingTo(override);
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 7: marking paid does not mutate the suggested snapshot")
    void markPaidDoesNotMutateTheSnapshot(@ForAll("money") BigDecimal suggested) {
        Fixture f = new Fixture();
        WorkerPayoutEntity payout = f.persistedPlanned();
        payout.setSuggestedAmount(suggested);
        payout.setAmount(suggested);
        when(f.workerPayoutDao.findById(payout.getId())).thenReturn(Optional.of(payout));

        f.service.markPaid(payout.getId());

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(payout.getSuggestedAmount())
                .as("suggestedAmount is never changed by a later operation (R1.4)")
                .isEqualByComparingTo(suggested);
    }

    // =============================================================================================
    // Property 8 — PLANNED → PAID only; PAID is terminal and immutable.
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 8: marking a PLANNED payout paid sets PAID + paidAt")
    void markingPlannedPaidSucceedsAndSetsPaidAt(@ForAll("payoutId") long id) {
        Fixture f = new Fixture();
        WorkerPayoutEntity payout = f.persistedPlanned();
        payout.setId(id);
        when(f.workerPayoutDao.findById(id)).thenReturn(Optional.of(payout));

        f.service.markPaid(id);

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(payout.getPaidAt()).isNotNull();
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 8: a PAID payout is terminal — mark-paid is rejected, status unchanged")
    void markingPaidAgainIsRejected(@ForAll("payoutId") long id) {
        Fixture f = new Fixture();
        WorkerPayoutEntity payout = f.persistedPlanned();
        payout.setId(id);
        payout.setStatus(PayoutStatus.PAID);
        when(f.workerPayoutDao.findById(id)).thenReturn(Optional.of(payout));

        assertApiError(() -> f.service.markPaid(id),
                HttpStatus.CONFLICT, "error.workerPayout.status.illegalTransition");
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAID); // unchanged
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 8: a PAID payout is terminal — delete is rejected, nothing removed")
    void deletingPaidIsRejected(@ForAll("payoutId") long id) {
        Fixture f = new Fixture();
        WorkerPayoutEntity payout = f.persistedPlanned();
        payout.setId(id);
        payout.setStatus(PayoutStatus.PAID);
        when(f.workerPayoutDao.findById(id)).thenReturn(Optional.of(payout));

        assertApiError(() -> f.service.delete(id),
                HttpStatus.CONFLICT, "error.workerPayout.status.illegalTransition");
        verify(f.workerPayoutDao, never()).deleteById(anyLong());
        verify(f.reportLineLinker, never()).release(anyLong());
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-11-payroll, Property 8: a PLANNED regular payout can be hard-deleted (release + remove)")
    void deletingPlannedRegularSucceeds(@ForAll("payoutId") long id) {
        Fixture f = new Fixture();
        WorkerPayoutEntity payout = f.persistedPlanned();
        payout.setId(id);
        when(f.workerPayoutDao.findById(id)).thenReturn(Optional.of(payout));

        f.service.delete(id);

        verify(f.reportLineLinker).release(id);
        verify(f.workerPayoutDao).deleteById(id);
    }

    // =============================================================================================
    // fixture
    // =============================================================================================

    private static final class Fixture {
        final WorkerPayoutDao workerPayoutDao = mock(WorkerPayoutDao.class);
        final WorkReportRoomDao workReportRoomDao = mock(WorkReportRoomDao.class);
        final ProjectMemberDao projectMemberDao = mock(ProjectMemberDao.class);
        final ProjectAccessCache projectAccessCache = mock(ProjectAccessCache.class);
        final PayoutSuggestionService suggestionService = mock(PayoutSuggestionService.class);
        final ReportLineLinker reportLineLinker = mock(ReportLineLinker.class);
        final WorkerPayoutServiceMapper serviceMapper = mock(WorkerPayoutServiceMapper.class);
        final WorkerPayoutDtoMapper dtoMapper = mock(WorkerPayoutDtoMapper.class);
        final AuditLogDao auditLogDao = mock(AuditLogDao.class);
        final EntityManager entityManager = mock(EntityManager.class);
        final WorkerPayoutService service;
        private long idSeq = 7000L;

        Fixture() {
            service = new WorkerPayoutService(
                    workerPayoutDao, workReportRoomDao, projectMemberDao, projectAccessCache,
                    suggestionService, reportLineLinker, new AdvanceCreditCalculator(),
                    new AdvanceDeriver(), serviceMapper, dtoMapper, auditLogDao, entityManager);
            lenient().when(workerPayoutDao.save(any(WorkerPayoutEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            lenient().when(entityManager.getReference(any(), any()))
                    .thenAnswer(inv -> referenceStub(inv.getArgument(0), (Long) inv.getArgument(1)));
            lenient().when(dtoMapper.toDto(any(WorkerPayoutEntity.class)))
                    .thenReturn(new WorkerPayoutDto(1L, WORKER_ID, "W", FROM, REGULAR_TO,
                            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO,
                            PaymentChannel.CASH, PayoutStatus.PLANNED, false, null, null));
            ProjectMemberEntity member = new ProjectMemberEntity();
            member.setAssignmentStatus(AssignmentStatus.ACTIVE);
            lenient().when(projectMemberDao.findByUserIdAndProjectId(WORKER_ID, PROJECT_ID))
                    .thenReturn(Optional.of(member));
            authenticate(1L, "ROLE_ADMIN");
        }

        void stubSuggest(boolean advance, BigDecimal gross, BigDecimal applied, BigDecimal suggested) {
            PayoutSuggestionDto dto = new PayoutSuggestionDto(
                    gross, BigDecimal.ZERO, applied, suggested, advance, List.of());
            lenient().when(suggestionService.suggest(eq(PROJECT_ID), eq(WORKER_ID), any(), any(), eq(advance)))
                    .thenReturn(dto);
        }

        WorkerPayoutEntity persistedPlanned() {
            WorkerPayoutEntity p = new WorkerPayoutEntity();
            p.setId(++idSeq);
            p.setProject(referenceStub(ProjectEntity.class, PROJECT_ID));
            p.setWorker(referenceStub(UserEntity.class, WORKER_ID));
            p.setStatus(PayoutStatus.PLANNED);
            p.setAdvance(false);
            p.setPeriodFrom(FROM);
            p.setPeriodTo(REGULAR_TO);
            p.setAmount(BigDecimal.ONE);
            p.setSuggestedAmount(BigDecimal.ONE);
            p.setAdvanceApplied(BigDecimal.ZERO);
            p.setPaymentChannel(PaymentChannel.CASH);
            return p;
        }

        WorkerPayoutEntity capturedSave() {
            ArgumentCaptor<WorkerPayoutEntity> captor = ArgumentCaptor.forClass(WorkerPayoutEntity.class);
            verify(workerPayoutDao).save(captor.capture());
            return captor.getValue();
        }
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

    private static void authenticate(Long userId, String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "pw", List.of(new SimpleGrantedAuthority(authority))));
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

    // =============================================================================================
    // generators
    // =============================================================================================

    @Provide
    Arbitrary<BigDecimal> money() {
        return Arbitraries.longs().between(0L, 100_000_000L).map(c -> BigDecimal.valueOf(c, 2));
    }

    @Provide
    Arbitrary<Long> payoutId() {
        return Arbitraries.longs().between(1L, 1_000_000L);
    }
}
