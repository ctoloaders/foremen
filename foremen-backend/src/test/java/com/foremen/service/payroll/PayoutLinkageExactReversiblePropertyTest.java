package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import org.mockito.ArgumentCaptor;
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
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.WorkerPayoutServiceMapper;
import com.foremen.service.payroll.dto.CreatePayoutRequest;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.payroll.dto.WorkerPayoutDto;
import com.foremen.service.payroll.dto.mapper.WorkerPayoutDtoMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-11 task 8.1 — <b>Property 4: LinkageExactReversible</b> (design §Correctness Properties).
 *
 * <p>For any regular payout create, the set of lines the {@link ReportLineLinker} links equals the
 * covered unpaid available line set; an <b>advance</b> links <b>no</b> line; a {@code PLANNED} delete
 * releases exactly that payout's lines (create-then-delete is identity on the FK state); and
 * overriding the cash {@code amount} changes <b>neither</b> the linked set <b>nor</b> the
 * {@code advanceApplied} (both follow the gross, not the override).
 *
 * <p><b>Validates: Requirements 1.2, 4.3, 4.4, 6.1, 6.2, 6.3, 6.4</b>
 */
@Tag("Feature: FOR-06-11-payroll, Property 4: paidByPayout linkage is exact, reversible, and absent for advances; override-independent")
class PayoutLinkageExactReversiblePropertyTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long WORKER_ID = 500L;
    private static final LocalDate FROM = LocalDate.now().minusMonths(1);
    private static final LocalDate REGULAR_TO = LocalDate.now().minusDays(1);
    private static final LocalDate ADVANCE_TO = LocalDate.now().plusMonths(1);

    @Property(tries = 150)
    @Tag("Feature: FOR-06-11-payroll, Property 4: a regular create links exactly the covered unpaid lines; an advance links none")
    void regularLinksExactlyTheCoveredLinesAndAdvanceLinksNone(
            @ForAll("lineCount") int lineCount,
            @ForAll("advance") boolean advance,
            @ForAll("money") BigDecimal gross) {

        Fixture f = new Fixture();
        List<WorkReportRoomEntity> lines = linesOf(lineCount);
        f.stubSuggest(advance, gross);
        if (!advance) {
            when(f.suggestionService.linkableLines(PROJECT_ID, WORKER_ID, FROM, REGULAR_TO))
                    .thenReturn(lines);
        }

        LocalDate to = advance ? ADVANCE_TO : REGULAR_TO;
        f.service.create(PROJECT_ID, new CreatePayoutRequest(
                WORKER_ID, FROM, to, PaymentChannel.CASH, null, null));

        if (advance) {
            // an advance links NO line and never resolves a linkable set (R6.4).
            verify(f.reportLineLinker, never()).link(any(), any());
        } else {
            ArgumentCaptor<List<WorkReportRoomEntity>> captor = listCaptor();
            verify(f.reportLineLinker).link(any(WorkerPayoutEntity.class), captor.capture());
            assertThat(captor.getValue())
                    .as("a regular create links exactly the covered unpaid available lines (R4.3, R6.1)")
                    .containsExactlyElementsOf(lines);
        }
    }

    @Property(tries = 150)
    @Tag("Feature: FOR-06-11-payroll, Property 4: a PLANNED delete releases exactly that payout's lines (round-trip identity)")
    void deletingAPlannedRegularReleasesItsLines(@ForAll("payoutId") long payoutId) {
        Fixture f = new Fixture();
        WorkerPayoutEntity payout = f.persistedRegularPlanned(payoutId);
        when(f.workerPayoutDao.findById(payoutId)).thenReturn(Optional.of(payout));

        f.service.delete(payoutId);

        // create-then-delete identity on the FK state: delete clears exactly this payout's FKs (R6.3).
        verify(f.reportLineLinker).release(payoutId);
        verify(f.workerPayoutDao).deleteById(payoutId);
    }

    @Property(tries = 150)
    @Tag("Feature: FOR-06-11-payroll, Property 4: overriding the cash amount changes neither the linked set nor advanceApplied")
    void overridingAmountChangesNeitherLinkageNorCredit(
            @ForAll("money") BigDecimal override,
            @ForAll("lineCount") int lineCount) {

        Fixture f = new Fixture();
        // gross 300, applied credit 120 (independent of the override).
        f.stubSuggest(false, new BigDecimal("300"), new BigDecimal("120"), new BigDecimal("180"));
        List<WorkReportRoomEntity> lines = linesOf(lineCount);
        when(f.suggestionService.linkableLines(PROJECT_ID, WORKER_ID, FROM, REGULAR_TO))
                .thenReturn(lines);

        f.service.create(PROJECT_ID, new CreatePayoutRequest(
                WORKER_ID, FROM, REGULAR_TO, PaymentChannel.CASH, override, null));

        WorkerPayoutEntity saved = f.capturedSave();
        assertThat(saved.getAmount())
                .as("the overridden cash amount is honored")
                .isEqualByComparingTo(override);
        assertThat(saved.getSuggestedAmount())
                .as("the suggested snapshot is independent of the override")
                .isEqualByComparingTo("180");
        assertThat(saved.getAdvanceApplied())
                .as("advanceApplied follows the gross, not the override (R4.4)")
                .isEqualByComparingTo("120");
        ArgumentCaptor<List<WorkReportRoomEntity>> captor = listCaptor();
        verify(f.reportLineLinker).link(any(WorkerPayoutEntity.class), captor.capture());
        assertThat(captor.getValue())
                .as("the linked set follows the gross, not the override (R4.4)")
                .containsExactlyElementsOf(lines);
    }

    // =======================================================================
    // helpers / fixture
    // =======================================================================

    private static List<WorkReportRoomEntity> linesOf(int n) {
        List<WorkReportRoomEntity> lines = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            WorkReportRoomEntity l = new WorkReportRoomEntity();
            l.setId((long) (i + 1));
            lines.add(l);
        }
        return lines;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<WorkReportRoomEntity>> listCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    /** A freshly-wired service with mocked collaborators and an authenticated ADMIN caller. */
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

        void stubSuggest(boolean advance, BigDecimal gross) {
            BigDecimal applied = advance ? BigDecimal.ZERO : BigDecimal.ZERO;
            stubSuggest(advance, gross, applied, gross);
        }

        void stubSuggest(boolean advance, BigDecimal gross, BigDecimal applied, BigDecimal suggested) {
            PayoutSuggestionDto dto = new PayoutSuggestionDto(
                    gross, BigDecimal.ZERO, applied, suggested, advance, List.of());
            lenient().when(suggestionService.suggest(eq(PROJECT_ID), eq(WORKER_ID), any(), any(), eq(advance)))
                    .thenReturn(dto);
        }

        WorkerPayoutEntity persistedRegularPlanned(long id) {
            WorkerPayoutEntity p = new WorkerPayoutEntity();
            p.setId(id);
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

    // =======================================================================
    // generators
    // =======================================================================

    @Provide
    Arbitrary<Integer> lineCount() {
        return Arbitraries.integers().between(0, 6);
    }

    @Provide
    Arbitrary<Boolean> advance() {
        return Arbitraries.of(true, false);
    }

    @Provide
    Arbitrary<Long> payoutId() {
        return Arbitraries.longs().between(1L, 1_000_000L);
    }

    @Provide
    Arbitrary<BigDecimal> money() {
        return Arbitraries.longs().between(0L, 100_000_000L).map(c -> BigDecimal.valueOf(c, 2));
    }
}
