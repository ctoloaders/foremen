package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.WorkReportRoomDao;
import com.foremen.dao.WorkerPayoutAllocationDao;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportInputMode;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkReportStatus;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.payroll.dto.CompanyPayrollDto;
import com.foremen.service.payroll.dto.CompanyPayrollProjectRowDto;
import com.foremen.service.payroll.dto.CompanyPayrollRowDto;
import com.foremen.service.payroll.dto.CreatePayoutRequest;
import com.foremen.service.payroll.dto.PayoutAllocationDto;
import com.foremen.service.payroll.dto.PayoutLineDto;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.payroll.dto.WorkerPayoutDto;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-07-06 task 7.2 — company-payroll integration tests against a real PostgreSQL
 * (Testcontainers) over genuine, committed transactions (Requirements 5.3, 5.4, 5.8, 5.9, 6.2, 6.3,
 * 7.1, 10.3, 17.2).
 *
 * <p>Complements {@link WorkerPayoutServiceIntegrationTest} (rollback, two-line distribution, list,
 * audit) with what only a real database can prove:
 * <ul>
 *   <li><b>Distribution order across projects (R4.3, R5.3, R5.4, R6.2).</b> Three lines on two
 *       projects, persisted out of chronological order: the payout follows {@code performedFrom}, not
 *       insertion order — oldest full, the middle one partial, the newest untouched; per line
 *       {@code paid_amount = Σ allocations} (R5.9); the company table attributes Paid to both projects
 *       (R10.3); delete restores every line.</li>
 *   <li><b>Concurrent creates do not over-allocate (R5.8).</b> Two transactions create a full payout
 *       for the same worker at the same time; the second blocks on the {@code PESSIMISTIC_WRITE}
 *       candidate-line lock until the first commits, then sees the lines paid and allocates nothing.</li>
 *   <li><b>Worker-wide advance credit (R7.1, R6.3).</b> One advance is applied across lines on two
 *       projects; once consumed it cannot be deleted.</li>
 * </ul>
 *
 * <p>Repeatability: per-run ids ({@code runId}) on every row; {@link #cleanUp()} deletes them
 * deepest-FK-first (allocations → report lines → payouts → reports → rooms → projects → users →
 * catalog) after each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
@Tag("Feature: FOR-07-06-company-payroll, task 7.2: distribution order, concurrency, worker-wide credit")
class CompanyPayrollIntegrationTest {

    private static final AtomicLong COUNTER = new AtomicLong();

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
    private WorkerPayoutService workerPayoutService;
    @Autowired
    private PayrollReadModelService readModelService;
    @Autowired
    private WorkerPayoutAllocationDao allocationDao;
    @Autowired
    private WorkReportRoomDao workReportRoomDao;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        tx().executeWithoutResult(status -> {
            exec("delete from WorkerPayoutAllocationEntity a where a.payout.id in "
                    + "(select p.id from WorkerPayoutEntity p where p.worker.email like :p)");
            exec("delete from WorkReportRoomEntity rr where rr.report.id in "
                    + "(select r.id from WorkReportEntity r where r.project.name like :p)");
            exec("delete from WorkerPayoutEntity p where p.worker.id in "
                    + "(select u.id from UserEntity u where u.email like :p)");
            exec("delete from WorkReportEntity r where r.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from RoomEntity rm where rm.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from ProjectEntity pr where pr.name like :p");
            exec("delete from UserEntity u where u.email like :p");
            exec("delete from WorkItemEntity wi where wi.code like :p");
            exec("delete from WorkCategoryEntity wc where wc.code like :p");
            exec("delete from WorkerTypeEntity w where w.code like :p");
            exec("delete from RoomTypeEntity rt where rt.code like :p");
        });
    }

    private void exec(String jpql) {
        entityManager.createQuery(jpql).setParameter("p", "%" + runId + "%").executeUpdate();
    }

    // =====================================================================================
    // Oldest-first across two projects, one partial line, delete reversal
    // =====================================================================================

    @Test
    @DisplayName("Three lines on two projects (inserted newest-first): oldest full, middle partial, newest untouched; delete reverses")
    void distributesOldestFirstAcrossProjectsWithOnePartialLine() {
        // Offsets are days before today; persisted newest-first so ids do not match the order.
        Fixture f = seed(new int[] {2, 6, 9}, new int[] {1, 0, 1});
        authenticateAs(f.workerId, "ROLE_FINANCIER");
        // Distribution order = performedFrom ascending: 9, 6, 2 days ago.
        List<Long> expectedOrder = List.of(f.lineIds.get(2), f.lineIds.get(1), f.lineIds.get(0));

        PayoutSuggestionDto preview = workerPayoutService.suggest(f.workerId, from(), to());
        assertThat(preview.breakdown()).extracting(PayoutLineDto::workReportRoomId)
                .as("oldest report first, regardless of insertion order (R4.3)")
                .containsExactlyElementsOf(expectedOrder);
        BigDecimal oldest = preview.breakdown().get(0).remainder();
        BigDecimal middle = preview.breakdown().get(1).remainder();
        BigDecimal part = middle.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);

        WorkerPayoutDto payout = workerPayoutService.create(request(f.workerId, oldest.add(part)));

        assertThat(paidOf(expectedOrder.get(0))).as("oldest line fully paid").isEqualByComparingTo(oldest);
        assertThat(paidOf(expectedOrder.get(1))).as("the single partial line (R5.4)").isEqualByComparingTo(part);
        assertThat(paidOf(expectedOrder.get(2))).as("newest line untouched").isEqualByComparingTo("0");
        for (Long lineId : f.lineIds) {
            assertThat(paidOf(lineId)).as("paid_amount = Σ allocations for line %s (R5.9)", lineId)
                    .isEqualByComparingTo(allocatedTo(lineId));
        }
        assertThat(workerPayoutService.allocations(payout.id()))
                .extracting(PayoutAllocationDto::lineId)
                .containsExactly(expectedOrder.get(0), expectedOrder.get(1));

        // R10.3 — the company table spreads Paid over the two projects the allocations touched.
        CompanyPayrollDto table = readModelService.companyTable(from(), LocalDate.now(), List.of(f.workerId));
        assertThat(table.rows()).hasSize(1);
        CompanyPayrollRowDto row = table.rows().get(0);
        assertThat(row.sums().paid()).isEqualByComparingTo(payout.amount());
        assertThat(row.projects()).extracting(CompanyPayrollProjectRowDto::projectId)
                .containsExactlyInAnyOrderElementsOf(f.projectIds);
        BigDecimal projectPaid = row.projects().stream().map(CompanyPayrollProjectRowDto::paid)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(projectPaid).isEqualByComparingTo(payout.amount());

        workerPayoutService.delete(payout.id());

        for (Long lineId : f.lineIds) {
            assertThat(paidOf(lineId)).as("delete restores line %s (R6.2)", lineId).isEqualByComparingTo("0");
        }
        assertThat(allocationDao.findByPayoutId(payout.id())).isEmpty();
    }

    // =====================================================================================
    // Concurrent creates (R5.8)
    // =====================================================================================

    @Test
    @DisplayName("Two concurrent full creates for one worker: the second waits on the line lock and allocates nothing (R5.8)")
    void concurrentCreatesDoNotOverAllocate() throws Exception {
        Fixture f = seed(new int[] {8, 4}, new int[] {0, 1});
        authenticateAs(f.workerId, "ROLE_MANAGER");
        BigDecimal gross = workerPayoutService.suggest(f.workerId, from(), to()).gross();
        assertThat(gross.signum()).isPositive();

        CountDownLatch firstAllocated = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // T1: create inside an outer transaction and hold it open (locks held) until released.
            Future<WorkerPayoutDto> first = pool.submit(() -> asWorkerContext(f.workerId, () ->
                    tx().execute(status -> {
                        WorkerPayoutDto dto = workerPayoutService.create(request(f.workerId, gross));
                        firstAllocated.countDown();
                        await(releaseFirst);
                        return dto;
                    })));
            assertThat(firstAllocated.await(30, TimeUnit.SECONDS)).as("T1 allocated").isTrue();

            // T2: the same create while T1 still holds the candidate-line locks.
            Future<WorkerPayoutDto> second = pool.submit(() -> asWorkerContext(f.workerId, () ->
                    workerPayoutService.create(request(f.workerId, gross))));
            assertThatThrownBy(() -> second.get(1500, TimeUnit.MILLISECONDS))
                    .as("T2 must block on the PESSIMISTIC_WRITE lock while T1 is uncommitted")
                    .isInstanceOf(TimeoutException.class);

            releaseFirst.countDown();
            WorkerPayoutDto p1 = first.get(30, TimeUnit.SECONDS);
            WorkerPayoutDto p2 = second.get(30, TimeUnit.SECONDS);

            assertThat(allocationDao.findByPayoutId(p1.id())).as("T1 allocated every line").hasSize(2);
            assertThat(allocationDao.findByPayoutId(p2.id()))
                    .as("T2 saw the committed paid amounts and allocated nothing").isEmpty();
            assertThat(p2.suggestedAmount()).as("T2's locked gross was 0").isEqualByComparingTo("0");

            BigDecimal totalPaid = BigDecimal.ZERO;
            for (Long lineId : f.lineIds) {
                assertThat(paidOf(lineId)).as("line %s paid = Σ allocations (R5.9)", lineId)
                        .isEqualByComparingTo(allocatedTo(lineId));
                totalPaid = totalPaid.add(paidOf(lineId));
            }
            assertThat(totalPaid).as("no line paid beyond its remainder: total = gross, not 2 × gross")
                    .isEqualByComparingTo(gross);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    // =====================================================================================
    // Worker-wide advance credit (R7.1, R6.3)
    // =====================================================================================

    @Test
    @DisplayName("An advance is credited worker-wide across two projects; once consumed it cannot be deleted (R7.1, R6.3)")
    void advanceCreditIsWorkerWide() {
        Fixture f = seed(new int[] {8, 4}, new int[] {0, 1});
        authenticateAs(f.workerId, "ROLE_FINANCIER");

        PayoutSuggestionDto before = workerPayoutService.suggest(f.workerId, from(), to());
        BigDecimal first = before.breakdown().get(0).remainder();
        BigDecimal second = before.breakdown().get(1).remainder();
        // Credit covers the whole project-A line and part of the project-B line.
        BigDecimal advanceAmount = first.add(second.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));

        WorkerPayoutDto advance = workerPayoutService.create(new CreatePayoutRequest(
                f.workerId, null, LocalDate.now(), LocalDate.now().plusDays(30),
                PaymentChannel.CASH, advanceAmount, "advance " + runId));
        assertThat(advance.isAdvance()).isTrue();
        assertThat(allocationDao.findByPayoutId(advance.id())).as("an advance allocates nothing (R5.6)").isEmpty();

        PayoutSuggestionDto withCredit = workerPayoutService.suggest(f.workerId, from(), to());
        assertThat(withCredit.remainingCredit()).as("worker-wide credit (R7.1)").isEqualByComparingTo(advanceAmount);
        assertThat(withCredit.appliedCredit()).isEqualByComparingTo(advanceAmount);
        assertThat(withCredit.suggested()).isEqualByComparingTo(withCredit.gross().subtract(advanceAmount));

        // Default amount = suggested; D = suggested + applied = gross pays both projects' lines in full.
        WorkerPayoutDto regular = workerPayoutService.create(request(f.workerId, null));
        assertThat(regular.advanceApplied()).isEqualByComparingTo(advanceAmount);
        assertThat(regular.amount()).isEqualByComparingTo(withCredit.suggested());
        assertThat(paidOf(f.lineIds.get(0))).isEqualByComparingTo(first);
        assertThat(paidOf(f.lineIds.get(1))).isEqualByComparingTo(second);
        assertThat(workerPayoutService.allocations(regular.id()))
                .extracting(PayoutAllocationDto::projectId)
                .containsExactlyElementsOf(f.projectIds);

        assertThat(workerPayoutService.suggest(f.workerId, from(), to()).remainingCredit())
                .as("credit consumed, never negative (R7.2)").isEqualByComparingTo("0");

        assertThatThrownBy(() -> workerPayoutService.delete(advance.id()))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getMessageCode()).isEqualTo(WorkerPayoutService.ADVANCE_ALREADY_APPLIED);
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private static LocalDate from() {
        return LocalDate.now().minusDays(10);
    }

    private static LocalDate to() {
        return LocalDate.now().minusDays(1);
    }

    private CreatePayoutRequest request(Long workerId, BigDecimal amount) {
        return new CreatePayoutRequest(workerId, null, from(), to(), PaymentChannel.BANK_TRANSFER, amount,
                "regular " + runId);
    }

    private void authenticateAs(Long userId, String authority) {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        String.valueOf(userId), "n/a", List.of(new SimpleGrantedAuthority(authority))));
    }

    private <T> T asWorkerContext(Long userId, java.util.function.Supplier<T> body) {
        authenticateAs(userId, "ROLE_MANAGER");
        try {
            return body.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private BigDecimal paidOf(Long lineId) {
        return workReportRoomDao.findById(lineId).orElseThrow().getPaidAmount();
    }

    private BigDecimal allocatedTo(Long lineId) {
        BigDecimal sum = tx().execute(s -> entityManager.createQuery(
                        "select coalesce(sum(a.amount), 0) from WorkerPayoutAllocationEntity a "
                                + "where a.workReportRoom.id = :id", BigDecimal.class)
                .setParameter("id", lineId)
                .getSingleResult());
        return sum == null ? BigDecimal.ZERO : sum;
    }

    // =====================================================================================
    // Fixture building
    // =====================================================================================

    private static final class Fixture {
        Long workerId;
        List<Long> projectIds; // distinct projects, in creation order
        List<Long> lineIds;    // in insertion order
    }

    /**
     * Seeds one WORKER with one APPROVED report (one line, available cost) per entry of
     * {@code daysAgo}; {@code projectIndex[i]} picks project 0 or 1 for that report. Committed in one
     * transaction.
     */
    private Fixture seed(int[] daysAgo, int[] projectIndex) {
        Fixture f = new Fixture();
        tx().executeWithoutResult(status -> {
            MeasurementUnitEntity unit = getOrCreateUnit("m2");
            WorkerTypeEntity base = new WorkerTypeEntity();
            base.setCode("WT_BASE_" + runId + "_" + COUNTER.incrementAndGet());
            base.setTierPct(new BigDecimal("0.40"));
            base.setBase(true);
            base.setOrderNo(0);
            base.setNameRU("Базовый");
            base.setNamePL("Bazowy");
            base.setActive(true);
            entityManager.persist(base);

            WorkCategoryEntity category = new WorkCategoryEntity();
            category.setCode("CAT_" + runId + "_" + COUNTER.incrementAndGet());
            category.setOrderNo(1);
            category.setNameRU("Категория");
            category.setNamePL("Kategoria");
            category.setActive(true);
            entityManager.persist(category);

            WorkItemEntity item = new WorkItemEntity();
            item.setWorkCategory(category);
            item.setUnit(unit);
            item.setCode("WI_" + runId + "_" + COUNTER.incrementAndGet());
            item.setNameRU("Работа");
            item.setNamePL("Praca");
            item.setActive(true);
            entityManager.persist(item);

            RoleEntity workerRole = workerRole();
            UserEntity worker = new UserEntity();
            long seq = COUNTER.incrementAndGet();
            worker.setName("Worker " + runId + "-" + seq);
            worker.setEmail("worker-" + runId + "-" + seq + "@example.com");
            worker.setRole(workerRole);
            worker.setActive(true);
            worker.setLocale(java.util.Locale.forLanguageTag("pl"));
            entityManager.persist(worker);

            RoomTypeEntity roomType = new RoomTypeEntity();
            roomType.setCode("ROOMTYPE_" + runId + "_" + COUNTER.incrementAndGet());
            roomType.setNameRU("Комната");
            roomType.setNamePL("Pokój");
            roomType.setActive(true);
            entityManager.persist(roomType);

            List<ProjectEntity> projects = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                ProjectEntity project = new ProjectEntity();
                project.setName("Company Payroll IT " + runId + "-" + COUNTER.incrementAndGet());
                project.setStatus(i == 0 ? ProjectStatus.ACTIVE : ProjectStatus.COMPLETED);
                entityManager.persist(project);
                projects.add(project);
            }

            List<Long> lineIds = new ArrayList<>();
            for (int i = 0; i < daysAgo.length; i++) {
                ProjectEntity project = projects.get(projectIndex[i]);
                LocalDate performedFrom = LocalDate.now().minusDays(daysAgo[i]);

                RoomEntity room = new RoomEntity();
                room.setProject(project);
                room.setRoomType(roomType);
                room.setLabel("Room " + runId + "-" + COUNTER.incrementAndGet());
                entityManager.persist(room);

                WorkReportEntity report = new WorkReportEntity();
                report.setProject(project);
                report.setWorkItem(item);
                report.setAuthor(worker);
                report.setWorker(worker);
                report.setPerformedFrom(performedFrom);
                report.setPerformedTo(performedFrom.plusDays(1));
                report.setStatus(WorkReportStatus.APPROVED);
                report.setClientUnitPrice(new BigDecimal("100.00"));
                report.setTotalQuantity(new BigDecimal("2.000"));
                report.setRevenueValue(new BigDecimal("200.00"));
                entityManager.persist(report);

                WorkReportRoomEntity line = new WorkReportRoomEntity();
                line.setReport(report);
                line.setRoom(room);
                line.setQuantity(new BigDecimal("2.000"));
                line.setInputMode(WorkReportInputMode.ABSOLUTE);
                entityManager.persist(line);
                lineIds.add(line.getId());
            }
            entityManager.flush();

            f.workerId = worker.getId();
            f.projectIds = projects.stream().map(ProjectEntity::getId).toList();
            f.lineIds = lineIds;
        });
        return f;
    }

    /** The shared WORKER role (find-or-create; not deleted on clean-up). */
    private RoleEntity workerRole() {
        List<RoleEntity> existing = entityManager
                .createQuery("select r from RoleEntity r where r.code = 'WORKER'", RoleEntity.class)
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        RoleEntity role = new RoleEntity();
        role.setCode("WORKER");
        role.setNameRU("Работник");
        role.setNamePL("Pracownik");
        role.setSystem(true);
        entityManager.persist(role);
        return role;
    }

    private MeasurementUnitEntity getOrCreateUnit(String code) {
        List<MeasurementUnitEntity> existing = entityManager.createQuery(
                        "select u from MeasurementUnitEntity u where u.code = :code", MeasurementUnitEntity.class)
                .setParameter("code", code)
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode(code);
        unit.setNameRU("Ед. " + code);
        unit.setNamePL("Jedn. " + code);
        unit.setActive(true);
        entityManager.persist(unit);
        return unit;
    }
}
