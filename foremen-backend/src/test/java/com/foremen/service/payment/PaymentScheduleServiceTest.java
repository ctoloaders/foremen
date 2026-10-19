package com.foremen.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.EstimateDao;
import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.PaymentScheduleServiceMapper;
import com.foremen.service.offer.AgreedOfferView;
import com.foremen.service.payment.dto.CreateScheduleRequest;
import com.foremen.service.payment.dto.GenerateRequest;
import com.foremen.service.payment.dto.PaymentScheduleView;
import com.foremen.service.payment.dto.RePlanRequest;
import com.foremen.service.payment.dto.mapper.PaymentDtoMapperImpl;

import jakarta.persistence.EntityManager;

/**
 * Plain Mockito unit tests for {@link PaymentScheduleService} (FOR-06-07 task 5.1), covering the
 * create / generate / publish / re-plan lifecycle established by the task. The DAOs, offer service,
 * access cache, audit, and entity manager are mocked; the real pure collaborators
 * ({@link TrancheGenerator}, {@link TrancheStatusDeriver}) and the real {@code PaymentDtoMapperImpl}
 * are used so the sum/seq/status invariants are exercised end-to-end. The caller is driven as ADMIN
 * through {@link SecurityContextHolder} so the project-access gate is bypassed.
 *
 * <p>Covered: seed from the APPROVED offer total (R1.2), reject when no total (R1.3), duplicate
 * schedule reject (R1.1), generator replaces tranches sum-exactly (R2.6), publish happy path (R4.1)
 * + sum-mismatch / zero-tranche reject (R4.2), and re-plan re-sums the unpaid remainder leaving the
 * PAID tranche immutable (R6.1, R6.2).
 */
@ExtendWith(MockitoExtension.class)
class PaymentScheduleServiceTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long SCHEDULE_ID = 7L;

    @Mock
    private PaymentScheduleDao paymentScheduleDao;
    @Mock
    private PaymentTransactionDao paymentTransactionDao;
    @Mock
    private ProjectDao projectDao;
    @Mock
    private EstimateDao estimateDao;
    @Mock
    private OfferService offerService;
    @Mock
    private PaymentScheduleServiceMapper serviceMapper;
    @Mock
    private ProjectAccessCache projectAccessCache;
    @Mock
    private AuditLogDao auditLogDao;
    @Mock
    private EntityManager entityManager;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private PaymentScheduleService service;

    @BeforeEach
    void setUp() {
        service = new PaymentScheduleService(
                paymentScheduleDao, paymentTransactionDao, projectDao, estimateDao, offerService,
                new TrancheGenerator(), new TrancheStatusDeriver(), new PaymentDtoMapperImpl(),
                serviceMapper, projectAccessCache, auditLogDao, entityManager, eventPublisher);

        // ADMIN caller -> bypasses the project-access gate (ProjectScopedService + loadAccessibleProject).
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        lenient().when(paymentScheduleDao.save(any(PaymentScheduleEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ProjectEntity project() {
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        return project;
    }

    private AgreedOfferView agreedTotal(String gross) {
        return new AgreedOfferView(1L, 2L, 1, "BASIC",
                new BigDecimal(gross), BigDecimal.ZERO, new BigDecimal(gross), List.of());
    }

    // --- create (R1.1, R1.2, R1.3) ---

    @Test
    @DisplayName("create seeds basisGross from the APPROVED offer total and starts DRAFT (R1.2)")
    void createSeedsBasisFromApprovedOffer() {
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(project()));
        when(paymentScheduleDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(offerService.agreedOfferViewByProject(PROJECT_ID)).thenReturn(agreedTotal("12000.00"));
        when(estimateDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());

        PaymentScheduleView view = service.create(new CreateScheduleRequest(PROJECT_ID, ScheduleSource.GENERATED));

        assertThat(view.basisGross()).isEqualByComparingTo("12000.00");
        assertThat(view.status()).isEqualTo(ScheduleStatus.DRAFT);
        assertThat(view.currency()).isEqualTo("PLN");
        assertThat(view.tranches()).isEmpty();
    }

    @Test
    @DisplayName("create rejects a project with no APPROVED-offer total (R1.3)")
    void createRejectsWhenNoProjectTotal() {
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(project()));
        when(paymentScheduleDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(offerService.agreedOfferViewByProject(PROJECT_ID))
                .thenThrow(new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found"));

        assertThatThrownBy(() -> service.create(new CreateScheduleRequest(PROJECT_ID, null)))
                .isInstanceOf(ForemenApiException.class)
                .extracting(e -> ((ForemenApiException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("create rejects a second schedule for the same project (R1.1)")
    void createRejectsDuplicateSchedule() {
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(project()));
        when(paymentScheduleDao.findByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(new PaymentScheduleEntity()));

        assertThatThrownBy(() -> service.create(new CreateScheduleRequest(PROJECT_ID, null)))
                .isInstanceOf(ForemenApiException.class)
                .extracting(e -> ((ForemenApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // --- generate (R2.6) ---

    @Test
    @DisplayName("generate replaces tranches with a sum-exact, contiguous set on a DRAFT schedule (R2.6)")
    void generateReplacesTranchesSumExactly() {
        PaymentScheduleEntity schedule = draftSchedule("12000.00");
        when(paymentScheduleDao.findById(SCHEDULE_ID)).thenReturn(Optional.of(schedule));

        GenerateRequest req = new GenerateRequest(
                GeneratorStrategy.EQUAL, 3, null, LocalDate.of(2026, 1, 1), Periodicity.MONTHLY, null);
        PaymentScheduleView view = service.generate(SCHEDULE_ID, req);

        assertThat(view.tranches()).hasSize(3);
        assertThat(view.sumAmountGross()).isEqualByComparingTo("12000.00");
        assertThat(view.tranches()).extracting(t -> t.seqNo()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("generate on a non-DRAFT schedule is rejected (R1.5)")
    void generateRejectedWhenNotDraft() {
        PaymentScheduleEntity schedule = draftSchedule("12000.00");
        schedule.setStatus(ScheduleStatus.PUBLISHED);
        when(paymentScheduleDao.findById(SCHEDULE_ID)).thenReturn(Optional.of(schedule));

        GenerateRequest req = new GenerateRequest(
                GeneratorStrategy.EQUAL, 3, null, LocalDate.of(2026, 1, 1), Periodicity.MONTHLY, null);
        assertThatThrownBy(() -> service.generate(SCHEDULE_ID, req))
                .isInstanceOf(ForemenApiException.class)
                .extracting(e -> ((ForemenApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // --- publish (R4.1, R4.2) ---

    @Test
    @DisplayName("publish transitions DRAFT -> PUBLISHED when the tranche sum reconciles (R4.1)")
    void publishHappyPath() {
        PaymentScheduleEntity schedule = draftSchedule("12000.00");
        addTranche(schedule, 1, "6000.00", "0", LocalDate.of(2099, 1, 1));
        addTranche(schedule, 2, "6000.00", "0", LocalDate.of(2099, 2, 1));
        when(paymentScheduleDao.findById(SCHEDULE_ID)).thenReturn(Optional.of(schedule));

        PaymentScheduleView view = service.publish(SCHEDULE_ID);

        assertThat(view.status()).isEqualTo(ScheduleStatus.PUBLISHED);
    }

    @Test
    @DisplayName("publish is rejected when the tranche sum does not reconcile (R4.2)")
    void publishRejectedOnSumMismatch() {
        PaymentScheduleEntity schedule = draftSchedule("12000.00");
        addTranche(schedule, 1, "5000.00", "0", LocalDate.of(2099, 1, 1));
        when(paymentScheduleDao.findById(SCHEDULE_ID)).thenReturn(Optional.of(schedule));

        assertThatThrownBy(() -> service.publish(SCHEDULE_ID))
                .isInstanceOf(ForemenApiException.class)
                .extracting(e -> ((ForemenApiException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("publish is rejected when the schedule has no tranches (R4.2)")
    void publishRejectedOnZeroTranches() {
        PaymentScheduleEntity schedule = draftSchedule("12000.00");
        when(paymentScheduleDao.findById(SCHEDULE_ID)).thenReturn(Optional.of(schedule));

        assertThatThrownBy(() -> service.publish(SCHEDULE_ID))
                .isInstanceOf(ForemenApiException.class)
                .extracting(e -> ((ForemenApiException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // --- cannot-unpublish guard (R4.4) ---

    @Test
    @DisplayName("assertCanUnpublish rejects a schedule with a CONFIRMED allocated transaction (R4.4)")
    void cannotUnpublishWithConfirmedAllocation() {
        PaymentScheduleEntity schedule = draftSchedule("12000.00");
        schedule.setStatus(ScheduleStatus.PUBLISHED);

        com.foremen.dao.model.PaymentTransactionEntity confirmed =
                new com.foremen.dao.model.PaymentTransactionEntity();
        confirmed.setStatus(TransactionStatus.CONFIRMED);
        confirmed.getAllocations().add(new com.foremen.dao.model.TrancheAllocationEntity());
        when(paymentTransactionDao.findByScheduleIdOrderByIdAsc(SCHEDULE_ID))
                .thenReturn(List.of(confirmed));

        assertThatThrownBy(() -> service.assertCanUnpublish(schedule))
                .isInstanceOf(ForemenApiException.class)
                .extracting(e -> ((ForemenApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("assertCanUnpublish allows a schedule with no CONFIRMED allocated transaction (R4.4)")
    void canUnpublishWithoutConfirmedAllocation() {
        PaymentScheduleEntity schedule = draftSchedule("12000.00");
        schedule.setStatus(ScheduleStatus.PUBLISHED);

        // A PENDING transaction (even if it somehow has an allocation) does not block un-publish; and
        // a CONFIRMED transaction with no allocation (e.g. an unmatched line) does not block either.
        com.foremen.dao.model.PaymentTransactionEntity pending =
                new com.foremen.dao.model.PaymentTransactionEntity();
        pending.setStatus(TransactionStatus.PENDING);
        com.foremen.dao.model.PaymentTransactionEntity confirmedNoAlloc =
                new com.foremen.dao.model.PaymentTransactionEntity();
        confirmedNoAlloc.setStatus(TransactionStatus.CONFIRMED);
        when(paymentTransactionDao.findByScheduleIdOrderByIdAsc(SCHEDULE_ID))
                .thenReturn(List.of(pending, confirmedNoAlloc));

        service.assertCanUnpublish(schedule); // no throw
    }

    // --- re-plan (R6.1, R6.2) ---

    @Test
    @DisplayName("re-plan re-sums only the open tranches to the unpaid remainder, PAID tranche immutable (R6.1, R6.2)")
    void rePlanReAuthorsOpenRemainderKeepingPaidImmutable() {
        PaymentScheduleEntity schedule = draftSchedule("12000.00");
        schedule.setStatus(ScheduleStatus.PUBLISHED);
        // tranche 1 fully paid (immutable), tranche 2 open.
        addTranche(schedule, 1, "6000.00", "6000.00", LocalDate.of(2020, 1, 1));
        addTranche(schedule, 2, "6000.00", "0", LocalDate.of(2099, 2, 1));
        when(paymentScheduleDao.findById(SCHEDULE_ID)).thenReturn(Optional.of(schedule));

        // New total 15000 -> unpaid remainder = 15000 - 6000 = 9000 must land on the open tranche only.
        PaymentScheduleView view = service.rePlan(SCHEDULE_ID, new RePlanRequest(new BigDecimal("15000.00"), null));

        assertThat(view.basisGross()).isEqualByComparingTo("15000.00");
        assertThat(view.status()).isEqualTo(ScheduleStatus.PUBLISHED);
        assertThat(view.sumAmountGross()).isEqualByComparingTo("15000.00");

        var paid = view.tranches().stream().filter(t -> t.paidGross().signum() > 0).findFirst().orElseThrow();
        assertThat(paid.amountGross()).isEqualByComparingTo("6000.00"); // R6.2: PAID tranche unchanged.
        var open = view.tranches().stream().filter(t -> t.paidGross().signum() == 0).findFirst().orElseThrow();
        assertThat(open.amountGross()).isEqualByComparingTo("9000.00"); // R6.1: open re-sums to remainder.
    }

    // --- helpers ---

    private PaymentScheduleEntity draftSchedule(String basisGross) {
        PaymentScheduleEntity schedule = new PaymentScheduleEntity();
        schedule.setId(SCHEDULE_ID);
        schedule.setProject(project());
        schedule.setCurrency("PLN");
        schedule.setBasisGross(new BigDecimal(basisGross));
        schedule.setSource(ScheduleSource.GENERATED);
        schedule.setStatus(ScheduleStatus.DRAFT);
        schedule.setUnallocatedCredit(BigDecimal.ZERO);
        return schedule;
    }

    private void addTranche(PaymentScheduleEntity schedule, int seqNo, String amount, String paid,
                            LocalDate dueDate) {
        PaymentTrancheEntity tranche = new PaymentTrancheEntity();
        tranche.setSchedule(schedule);
        tranche.setSeqNo(seqNo);
        tranche.setDueDate(dueDate);
        tranche.setAmountGross(new BigDecimal(amount));
        tranche.setPaidGross(new BigDecimal(paid));
        tranche.setStatus(TrancheStatus.PENDING);
        schedule.getTranches().add(tranche);
    }
}
