package com.foremen.service.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.PaymentTransactionEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.service.payment.provider.BankFeedProvider;
import com.foremen.service.payment.provider.IncomingBankTxn;
import com.foremen.service.payment.provider.OnlineInitiateResult;
import com.foremen.service.payment.provider.OnlinePaymentProvider;
import com.foremen.service.payment.provider.RawWebhook;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-06-07 task 8.2 — Spring integration tests for the payment reconciliation wiring, booted
 * against a real PostgreSQL (Testcontainers) with the full application context: the real
 * {@link PaymentTransactionService} → {@link ReconciliationService} → {@link ReconciliationEngine} /
 * {@link TrancheStatusDeriver} chain, the real JPA DAOs, the cascade {@code TrancheAllocation}
 * writes, and the default config-selected No-Op providers.
 *
 * <p>Boot harness mirrors the repo's established estimate integration tests
 * ({@code MaterialsListServiceIntegrationTest}): {@code @SpringBootTest(MOCK)} + {@code @Testcontainers}
 * + {@code @ActiveProfiles("integration-test")}, where Liquibase is disabled and Hibernate
 * {@code create-drop} builds the schema, so every fixture row is created programmatically.
 *
 * <p>Covers the downstream wiring the pure/unit tests cannot assert end-to-end:
 *
 * <ul>
 *   <li><b>Bank-feed ingest → CONFIRMED → reconcile, idempotent on re-poll (R8.3, R5.8).</b> A
 *       correlated bank line is recorded {@code BANK_TRANSFER}/{@code CONFIRMED} and settles tranches
 *       through the real cascade; re-ingesting the same {@code externalRef} is a no-op (no second
 *       transaction, no double allocation) so {@code paidGross} is unchanged.</li>
 *   <li><b>Manual mark-tranche-paid end-to-end downstream (R5.12).</b> Marking the last open tranche
 *       paid records a {@code MANUAL}/{@code CONFIRMED} transaction, writes the allocation, sets
 *       {@code paidGross == amountGross}, and rolls the schedule up to {@code SETTLED}.</li>
 *   <li><b>Webhook signature-verification path (R7.5).</b> Under the default
 *       {@code NoOpOnlinePaymentProvider}, {@code verifySignature} rejects every raw webhook (no live
 *       credentials), and {@code initiate} returns a disabled result — so no live online webhook is
 *       ever accepted while credentials are absent.</li>
 * </ul>
 *
 * <p><b>Repeatability.</b> Every fixture project carries a unique per-run id (UUID substring) in its
 * name and {@link #cleanUp()} removes every row this test created (deepest-FK-first) after each test,
 * so the suite re-runs without manual DB cleanup.
 *
 * <p><b>Validates: Requirements 5.12, 7.5, 8.3</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
@DisplayName("Payment reconciliation wiring — bank-feed ingest, mark-paid roll-up, webhook signature")
class PaymentReconciliationIntegrationTest {

    private static final AtomicLong COUNTER = new AtomicLong();
    private static final int SCALE = 2;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private PaymentTransactionService paymentTransactionService;
    @Autowired
    private ReconciliationService reconciliationService;
    @Autowired
    private PaymentScheduleDao paymentScheduleDao;
    @Autowired
    private PaymentTransactionDao paymentTransactionDao;
    @Autowired
    private ProjectDao projectDao;
    @Autowired
    private OnlinePaymentProvider onlinePaymentProvider;
    @Autowired
    private BankFeedProvider bankFeedProvider;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    /** Unique run id so fixture project names never collide with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        tx().executeWithoutResult(status -> {
            // Deepest-FK-first: allocations -> transactions -> tranches -> schedules -> projects.
            entityManager.createQuery(
                            "delete from TrancheAllocationEntity a where a.tranche.schedule.project.id in "
                                    + "(select p.id from ProjectEntity p where p.name like :p)")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            entityManager.createQuery(
                            "delete from PaymentTransactionEntity t where t.schedule.project.id in "
                                    + "(select p.id from ProjectEntity p where p.name like :p)")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            entityManager.createQuery(
                            "delete from PaymentTrancheEntity tr where tr.schedule.project.id in "
                                    + "(select p.id from ProjectEntity p where p.name like :p)")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            entityManager.createQuery(
                            "delete from PaymentScheduleEntity s where s.project.id in "
                                    + "(select p.id from ProjectEntity p where p.name like :p)")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            entityManager.createQuery("delete from ProjectEntity p where p.name like :p")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
        });
    }

    /**
     * Seeds a PUBLISHED schedule with two equal open tranches of {@code trancheAmount} each, returning
     * the persisted schedule id. The owning project id is the bank-feed correlation key.
     */
    private long[] seedPublishedScheduleWithTwoTranches(BigDecimal trancheAmount) {
        return tx().execute(status -> {
            ProjectEntity project = new ProjectEntity();
            project.setName("Payments IT " + runId + " #" + COUNTER.incrementAndGet());
            project.setStatus(ProjectStatus.ACTIVE);
            project = projectDao.save(project);

            PaymentScheduleEntity schedule = new PaymentScheduleEntity();
            schedule.setProject(project);
            schedule.setCurrency("PLN");
            schedule.setBasisGross(trancheAmount.add(trancheAmount).setScale(SCALE));
            schedule.setSource(ScheduleSource.GENERATED);
            schedule.setStatus(ScheduleStatus.PUBLISHED);
            schedule.setUnallocatedCredit(BigDecimal.ZERO);

            PaymentTrancheEntity t1 = tranche(schedule, 1, trancheAmount, LocalDate.now().plusDays(10));
            PaymentTrancheEntity t2 = tranche(schedule, 2, trancheAmount, LocalDate.now().plusDays(40));
            schedule.getTranches().add(t1);
            schedule.getTranches().add(t2);

            PaymentScheduleEntity saved = paymentScheduleDao.save(schedule);
            entityManager.flush();
            return new long[]{saved.getId(), project.getId(),
                    saved.getTranches().get(0).getId(), saved.getTranches().get(1).getId()};
        });
    }

    private PaymentTrancheEntity tranche(PaymentScheduleEntity schedule, int seqNo,
                                         BigDecimal amount, LocalDate dueDate) {
        PaymentTrancheEntity tranche = new PaymentTrancheEntity();
        tranche.setSchedule(schedule);
        tranche.setSeqNo(seqNo);
        tranche.setDueDate(dueDate);
        tranche.setAmountGross(amount.setScale(SCALE));
        tranche.setPaidGross(BigDecimal.ZERO);
        tranche.setStatus(TrancheStatus.PENDING);
        return tranche;
    }

    // --- Bank-feed ingest -> CONFIRMED -> reconcile, idempotent on re-poll (R8.3, R5.8) ---

    @Test
    @DisplayName("bank-feed ingest records a CONFIRMED BANK_TRANSFER and reconciles; re-poll is a no-op (R8.3, R5.8)")
    void bankFeedIngestReconcilesAndIsIdempotentOnRePoll() {
        BigDecimal trancheAmount = new BigDecimal("500.00");
        long[] ids = seedPublishedScheduleWithTwoTranches(trancheAmount);
        long scheduleId = ids[0];
        long projectId = ids[1];
        long tranche1Id = ids[2];

        String externalRef = "bank-ref-" + runId;
        IncomingBankTxn line = new IncomingBankTxn(
                new BigDecimal("500.00"), "PLN", externalRef, Instant.now(), String.valueOf(projectId));

        // First ingest: the line correlates to the schedule, confirms, and settles tranche 1.
        tx().executeWithoutResult(s -> paymentTransactionService.ingestBankTransaction(line));

        tx().executeWithoutResult(s -> {
            List<PaymentTransactionEntity> txns =
                    paymentTransactionDao.findByScheduleIdOrderByIdAsc(scheduleId);
            assertThat(txns).as("exactly one transaction recorded after the first ingest").hasSize(1);
            PaymentTransactionEntity txn = txns.get(0);
            assertThat(txn.getChannel()).isEqualTo(PaymentChannel.BANK_TRANSFER);
            assertThat(txn.getStatus()).isEqualTo(TransactionStatus.CONFIRMED);
            assertThat(txn.getExternalRef()).isEqualTo(externalRef);

            PaymentScheduleEntity schedule = paymentScheduleDao.findById(scheduleId).orElseThrow();
            PaymentTrancheEntity t1 = schedule.getTranches().stream()
                    .filter(t -> t.getId().equals(tranche1Id)).findFirst().orElseThrow();
            // R8.3: the nearest-open tranche (seq 1) is settled by the 500.00 bank transfer.
            assertThat(t1.getPaidGross()).isEqualByComparingTo("500.00");
            assertThat(t1.getStatus()).isEqualTo(TrancheStatus.PAID);
        });

        // Re-poll the SAME externalRef: idempotent — no second transaction, no double allocation (R5.8).
        IncomingBankTxn rePoll = new IncomingBankTxn(
                new BigDecimal("500.00"), "PLN", externalRef, Instant.now(), String.valueOf(projectId));
        tx().executeWithoutResult(s -> paymentTransactionService.ingestBankTransaction(rePoll));

        tx().executeWithoutResult(s -> {
            List<PaymentTransactionEntity> txns =
                    paymentTransactionDao.findByScheduleIdOrderByIdAsc(scheduleId);
            assertThat(txns).as("re-polling the same externalRef must not record a second transaction (R5.8)")
                    .hasSize(1);
            PaymentScheduleEntity schedule = paymentScheduleDao.findById(scheduleId).orElseThrow();
            PaymentTrancheEntity t1 = schedule.getTranches().stream()
                    .filter(t -> t.getId().equals(tranche1Id)).findFirst().orElseThrow();
            // paidGross unchanged — no double allocation.
            assertThat(t1.getPaidGross()).isEqualByComparingTo("500.00");
        });
    }

    // --- Manual mark-tranche-paid end-to-end downstream (R5.12: allocation + SETTLED roll-up) ---

    @Test
    @DisplayName("marking every tranche paid writes allocations and rolls the schedule up to SETTLED (R5.12)")
    void markTranchePaidSettlesTrancheAndRollsScheduleToSettled() {
        BigDecimal trancheAmount = new BigDecimal("750.00");
        long[] ids = seedPublishedScheduleWithTwoTranches(trancheAmount);
        long scheduleId = ids[0];
        long tranche1Id = ids[2];
        long tranche2Id = ids[3];

        // Mark tranche 1 paid: it is settled, an allocation is written, but the schedule is not yet
        // SETTLED (tranche 2 still open).
        tx().executeWithoutResult(s -> reconciliationService.markTranchePaid(tranche1Id, null));

        tx().executeWithoutResult(s -> {
            PaymentScheduleEntity schedule = paymentScheduleDao.findById(scheduleId).orElseThrow();
            PaymentTrancheEntity t1 = schedule.getTranches().stream()
                    .filter(t -> t.getId().equals(tranche1Id)).findFirst().orElseThrow();
            assertThat(t1.getPaidGross()).isEqualByComparingTo("750.00");
            assertThat(t1.getStatus()).isEqualTo(TrancheStatus.PAID);
            assertThat(schedule.getStatus())
                    .as("one of two tranches paid — the schedule is not yet SETTLED")
                    .isEqualTo(ScheduleStatus.PUBLISHED);

            // R5.12: an auditable MANUAL/CONFIRMED transaction with an allocation to exactly tranche 1.
            List<PaymentTransactionEntity> txns =
                    paymentTransactionDao.findByScheduleIdOrderByIdAsc(scheduleId);
            assertThat(txns).hasSize(1);
            PaymentTransactionEntity manual = txns.get(0);
            assertThat(manual.getChannel()).isEqualTo(PaymentChannel.MANUAL);
            assertThat(manual.getStatus()).isEqualTo(TransactionStatus.CONFIRMED);
            assertThat(manual.getAmountGross()).isEqualByComparingTo("750.00");
            assertThat(manual.getAllocations()).hasSize(1);
            assertThat(manual.getAllocations().get(0).getTranche().getId()).isEqualTo(tranche1Id);
        });

        // Mark tranche 2 paid: now every tranche is PAID, so the schedule rolls up to SETTLED (R5.12).
        tx().executeWithoutResult(s -> reconciliationService.markTranchePaid(tranche2Id, null));

        tx().executeWithoutResult(s -> {
            PaymentScheduleEntity schedule = paymentScheduleDao.findById(scheduleId).orElseThrow();
            PaymentTrancheEntity t2 = schedule.getTranches().stream()
                    .filter(t -> t.getId().equals(tranche2Id)).findFirst().orElseThrow();
            assertThat(t2.getStatus()).isEqualTo(TrancheStatus.PAID);
            assertThat(schedule.getStatus())
                    .as("every tranche PAID — the schedule rolls up to SETTLED (R5.12, R5.6)")
                    .isEqualTo(ScheduleStatus.SETTLED);

            List<PaymentTransactionEntity> txns =
                    paymentTransactionDao.findByScheduleIdOrderByIdAsc(scheduleId);
            assertThat(txns).as("one MANUAL transaction per marked tranche").hasSize(2);
        });
    }

    // --- Webhook signature-verification path under the No-Op provider (R7.5) ---

    @Test
    @DisplayName("the default No-Op online provider verifies no live webhook and initiates disabled (R7.5)")
    void noOpOnlineProviderRejectsWebhookAndInitiatesDisabled() {
        // R7.5: with no credentials, verifySignature rejects every raw webhook, so no live online
        // webhook is ever accepted — the webhook controller would reject it 401 before parsing.
        RawWebhook raw = new RawWebhook("{\"ref\":\"x\"}".getBytes(), java.util.Map.of());
        assertThat(onlinePaymentProvider.verifySignature(raw))
                .as("the No-Op online provider must accept no live webhook while credentials are absent")
                .isFalse();

        // And initiate returns a disabled result (no external call, nothing CONFIRMED).
        OnlineInitiateResult result = onlinePaymentProvider.initiate(null);
        assertThat(result).isNotNull();
        assertThat(result.enabled())
                .as("the No-Op online provider initiate must return a disabled result").isFalse();

        // The default bank feed is empty (No-Op), so a trigger yields nothing.
        assertThat(bankFeedProvider.poll())
                .as("the No-Op bank feed yields no lines").isEmpty();
    }
}
