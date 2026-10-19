package com.foremen.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.foremen.dao.EstimateDao;
import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.service.OfferService;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.PaymentScheduleServiceMapper;
import com.foremen.service.payment.dto.PaymentScheduleView;
import com.foremen.service.payment.dto.RePlanRequest;
import com.foremen.service.payment.dto.mapper.PaymentDtoMapperImpl;

import jakarta.persistence.EntityManager;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterTry;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * FOR-06-07 task 8.1 — Property 6 "RePlanImmutability" for
 * {@link PaymentScheduleService#rePlan} (design §PaymentScheduleService; Requirements 6.1–6.4). After
 * an amendment changes the project total a re-plan:
 *
 * <ul>
 *   <li><b>leaves every {@code PAID} tranche immutable</b> — its {@code amountGross} and
 *       {@code paidGross} are unchanged (R6.2);</li>
 *   <li><b>re-sums the open tranches to the unpaid remainder</b> — the still-open (not-{@code PAID})
 *       tranches re-sum to {@code newBasisGross − Σ paidGross} (R6.1), so the whole schedule re-sums
 *       to {@code newBasisGross};</li>
 *   <li><b>stays {@code PUBLISHED}</b> and never un-publishes (R6.4);</li>
 *   <li>when the new remainder is non-positive (an overpay relative to the reduced total) it closes
 *       the open tranches and the {@code PAID} total alone carries the schedule, surfacing the excess
 *       as unallocated credit (R6.3).</li>
 * </ul>
 *
 * <p>The property uses the real {@link TrancheGenerator}, {@link TrancheStatusDeriver}, and
 * {@link PaymentDtoMapperImpl}; only the DAOs / access cache / audit / entity manager / event
 * publisher are mocked, and the caller is an ADMIN so the project-access gate is bypassed. It builds
 * a {@code PUBLISHED} schedule with an arbitrary number of {@code PAID} tranches (fixed amounts) and
 * open tranches, then re-plans to an arbitrary new total.
 *
 * <p><b>Validates: Requirements 6.1, 6.2, 6.3, 6.4</b>
 */
@Tag("Feature: FOR-06-07-client-payments, Property 6 RePlanImmutability")
class RePlanImmutabilityPropertyTest {

    private static final int SCALE = 2;
    private static final Long SCHEDULE_ID = 7L;
    private static final Long PROJECT_ID = 42L;

    private PaymentScheduleDao capturedScheduleDao;

    private PaymentScheduleService newService() {
        PaymentScheduleDao scheduleDao = mock(PaymentScheduleDao.class);
        when(scheduleDao.save(any(PaymentScheduleEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        this.capturedScheduleDao = scheduleDao;
        PaymentTransactionDao txnDao = mock(PaymentTransactionDao.class);
        ProjectDao projectDao = mock(ProjectDao.class);
        EstimateDao estimateDao = mock(EstimateDao.class);
        OfferService offerService = mock(OfferService.class);
        PaymentScheduleServiceMapper serviceMapper = mock(PaymentScheduleServiceMapper.class);
        ProjectAccessCache accessCache = mock(ProjectAccessCache.class);
        AuditLogDao auditLogDao = mock(AuditLogDao.class);
        EntityManager em = mock(EntityManager.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        return new PaymentScheduleService(scheduleDao, txnDao, projectDao, estimateDao, offerService,
                new TrancheGenerator(), new TrancheStatusDeriver(), new PaymentDtoMapperImpl(),
                serviceMapper, accessCache, auditLogDao, em, publisher);
    }

    @AfterTry
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /** A positive amount in [1.00, 50000], scale 2. */
    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("1.00"), new BigDecimal("50000"))
                .ofScale(SCALE);
    }

    /** 1..4 PAID tranche amounts. */
    @Provide
    Arbitrary<List<BigDecimal>> paidAmounts() {
        return amounts().list().ofMinSize(1).ofMaxSize(4);
    }

    /** 1..4 open tranche amounts (their values are irrelevant — they get re-authored). */
    @Provide
    Arbitrary<List<BigDecimal>> openAmounts() {
        return amounts().list().ofMinSize(1).ofMaxSize(4);
    }

    /** A new total in [1.00, 400000], scale 2. */
    @Provide
    Arbitrary<BigDecimal> newTotals() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("1.00"), new BigDecimal("400000"))
                .ofScale(SCALE);
    }

    private PaymentScheduleEntity buildSchedule(List<BigDecimal> paid, List<BigDecimal> open) {
        PaymentScheduleEntity schedule = new PaymentScheduleEntity();
        schedule.setId(SCHEDULE_ID);
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        schedule.setProject(project);
        schedule.setStatus(ScheduleStatus.PUBLISHED);
        schedule.setUnallocatedCredit(BigDecimal.ZERO);

        BigDecimal basis = BigDecimal.ZERO;
        int seq = 1;
        long id = 1;
        for (BigDecimal amount : paid) {
            BigDecimal a = amount.setScale(SCALE, RoundingMode.HALF_UP);
            PaymentTrancheEntity tranche = tranche(schedule, id++, seq++, a, a); // fully paid.
            schedule.getTranches().add(tranche);
            basis = basis.add(a);
        }
        for (BigDecimal amount : open) {
            BigDecimal a = amount.setScale(SCALE, RoundingMode.HALF_UP);
            PaymentTrancheEntity tranche = tranche(schedule, id++, seq++, a, BigDecimal.ZERO); // open.
            schedule.getTranches().add(tranche);
            basis = basis.add(a);
        }
        schedule.setBasisGross(basis);
        return schedule;
    }

    private static PaymentTrancheEntity tranche(PaymentScheduleEntity schedule, long id, int seq,
                                                BigDecimal amount, BigDecimal paid) {
        PaymentTrancheEntity tranche = new PaymentTrancheEntity();
        tranche.setId(id);
        tranche.setSchedule(schedule);
        tranche.setSeqNo(seq);
        tranche.setDueDate(LocalDate.of(2020, 1, 1).plusDays(seq)); // past due so paid ones derive PAID.
        tranche.setAmountGross(amount);
        tranche.setPaidGross(paid);
        tranche.setStatus(TrancheStatus.PENDING);
        return tranche;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    // Feature: FOR-06-07-client-payments, Property 6 RePlanImmutability
    // PAID tranches keep their amount/paid unchanged; open tranches re-sum to newBasis - Σpaid;
    // the schedule stays PUBLISHED/SETTLED (never DRAFT) and re-sums to the new total (R6.1–6.4).
    // Validates: Requirements 6.1, 6.2, 6.3, 6.4
    @Property(tries = 200)
    void rePlanKeepsPaidImmutableAndReSumsOpenToRemainder(
            @ForAll("paidAmounts") List<BigDecimal> paid,
            @ForAll("openAmounts") List<BigDecimal> open,
            @ForAll("newTotals") BigDecimal newTotal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        PaymentScheduleService service = newService();
        PaymentScheduleDao scheduleDao = capturedScheduleDao;

        PaymentScheduleEntity schedule = buildSchedule(paid, open);
        when(scheduleDao.findById(SCHEDULE_ID)).thenReturn(Optional.of(schedule));

        // Snapshot the PAID tranches by id (amount + paid) — these must be immutable (R6.2).
        List<Long> paidIds = new ArrayList<>();
        List<BigDecimal> paidAmountsBefore = new ArrayList<>();
        BigDecimal paidSum = BigDecimal.ZERO;
        for (PaymentTrancheEntity t : schedule.getTranches()) {
            if (t.getPaidGross().compareTo(t.getAmountGross()) >= 0) {
                paidIds.add(t.getId());
                paidAmountsBefore.add(t.getAmountGross());
                paidSum = paidSum.add(t.getPaidGross());
            }
        }

        BigDecimal newBasis = scale(newTotal);
        PaymentScheduleView view = service.rePlan(SCHEDULE_ID, new RePlanRequest(newBasis, null));

        // R6.4: never DRAFT after a re-plan (stays PUBLISHED, or rolls to SETTLED).
        assertThat(view.status()).isIn(ScheduleStatus.PUBLISHED, ScheduleStatus.SETTLED);
        // basis recomputed to the new total.
        assertThat(view.basisGross()).isEqualByComparingTo(newBasis);

        // R6.2: every PAID tranche is unchanged (amount + paid preserved, still present).
        for (int i = 0; i < paidIds.size(); i++) {
            Long id = paidIds.get(i);
            PaymentTrancheEntity t = findById(schedule, id);
            assertThat(t).as("PAID tranche %s must survive re-plan", id).isNotNull();
            assertThat(t.getAmountGross()).isEqualByComparingTo(paidAmountsBefore.get(i));
            assertThat(t.getPaidGross()).isEqualByComparingTo(paidAmountsBefore.get(i));
        }

        BigDecimal remainder = newBasis.subtract(paidSum);
        BigDecimal openSum = BigDecimal.ZERO;
        for (PaymentTrancheEntity t : schedule.getTranches()) {
            if (!paidIds.contains(t.getId())) {
                openSum = openSum.add(t.getAmountGross() == null ? BigDecimal.ZERO : t.getAmountGross());
            }
        }

        if (remainder.signum() > 0) {
            // R6.1: the open tranches re-sum to exactly the unpaid remainder...
            assertThat(scale(openSum)).isEqualByComparingTo(scale(remainder));
            // ...so the whole schedule re-sums to the new total.
            assertThat(view.sumAmountGross()).isEqualByComparingTo(newBasis);
        } else {
            // R6.3: non-positive remainder -> open tranches closed (removed); only PAID amount remains.
            assertThat(scale(openSum)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(view.sumAmountGross()).isEqualByComparingTo(scale(paidSum));
            assertThat(view.unallocatedCredit()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
    }

    private static PaymentTrancheEntity findById(PaymentScheduleEntity schedule, Long id) {
        for (PaymentTrancheEntity t : schedule.getTranches()) {
            if (id.equals(t.getId())) {
                return t;
            }
        }
        return null;
    }
}
