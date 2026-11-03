package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkReportDao;
import com.foremen.dao.WorkReportRoomDao;
import com.foremen.dao.WorkerPayoutAllocationDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportInputMode;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkReportStatus;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.payroll.dto.CreatePayoutRequest;
import com.foremen.service.payroll.dto.PayoutAllocationDto;
import com.foremen.service.payroll.dto.PayoutFilter;
import com.foremen.service.payroll.dto.PayoutLineDto;
import com.foremen.service.payroll.dto.PayoutSuggestionDto;
import com.foremen.service.payroll.dto.WorkerPayoutDto;
import com.foremen.service.workercost.WorkerCostService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-06-11 task 8.2, reshaped by FOR-07-06 task 5.2 — service-level integration tests for the
 * <b>company-wide</b> {@link WorkerPayoutService} against a real PostgreSQL (Testcontainers), over
 * genuine transactions and the existing audit mechanism.
 *
 * <ul>
 *   <li><b>Transactional create (R5.8).</b> {@link AllocationWriter#write} forced to throw → no payout
 *       row, no allocation, {@code paid_amount} unchanged.</li>
 *   <li><b>Worker-wide distribution + reversal (R5.3, R5.4, R6.2).</b> One worker, lines on two
 *       projects: the payout fills the older line and partially pays the newer one; {@code list} is
 *       not project-scoped for a non-ADMIN caller; {@code allocations} lists both lines; delete
 *       restores {@code paid_amount = 0}.</li>
 *   <li><b>Audit (R6.4).</b> Create, mark-paid, and delete each write a {@code WorkerPayoutEntity}
 *       audit row (for a non-ADMIN caller — no project scoping any more).</li>
 *   <li><b>Regression smoke.</b> {@code WorkerCostService} / {@code MarginMatrixService} still load
 *       and cost a project whose line carries a paid amount.</li>
 * </ul>
 *
 * <p>Repeatability: per-run ids ({@code runId}) on every row; {@link #cleanUp()} deletes them
 * deepest-FK-first after each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
@Tag("Feature: FOR-07-06-company-payroll, task 5.2: service integration — transactional/distribution/audit/regression")
class WorkerPayoutServiceIntegrationTest {

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

    /** Spy on the sole allocation mutator so the transactional test can force a mid-create failure. */
    @MockitoSpyBean
    private AllocationWriter allocationWriter;

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private WorkerCostService workerCostService;
    @Autowired
    private AuditLogDao auditLogDao;
    @Autowired
    private WorkerPayoutDao workerPayoutDao;
    @Autowired
    private WorkerPayoutAllocationDao allocationDao;
    @Autowired
    private WorkReportRoomDao workReportRoomDao;
    @Autowired
    private ProjectDao projectDao;
    @Autowired
    private MeasurementUnitDao measurementUnitDao;
    @Autowired
    private WorkCategoryDao workCategoryDao;
    @Autowired
    private WorkItemDao workItemDao;
    @Autowired
    private RoleDao roleDao;
    @Autowired
    private UserDao userDao;
    @Autowired
    private WorkReportDao workReportDao;
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
            // Deepest-FK-first: allocations → report lines → payouts → reports → rooms → projects → users.
            exec("delete from WorkerPayoutAllocationEntity a where a.payout.id in "
                    + "(select p.id from WorkerPayoutEntity p where p.worker.email like :p)");
            exec("delete from WorkReportRoomEntity rr where rr.report.id in "
                    + "(select r.id from WorkReportEntity r where r.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p))");
            exec("delete from WorkerPayoutEntity p where p.worker.id in "
                    + "(select u.id from UserEntity u where u.email like :p)");
            exec("delete from WorkReportEntity r where r.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from RoomEntity rm where rm.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from ProjectEntity pr where pr.name like :p");
            exec("delete from UserEntity u where u.email like :p");
            exec("delete from WorkItemEntity wi where wi.id in "
                    + "(select w.id from WorkItemEntity w where w.workCategory.code like :p)");
            exec("delete from WorkCategoryEntity wc where wc.code like :p");
            exec("delete from WorkerTypeEntity w where w.code like :p");
            exec("delete from RoomTypeEntity rt where rt.code like :p");
        });
    }

    private void exec(String jpql) {
        entityManager.createQuery(jpql).setParameter("p", "%" + runId + "%").executeUpdate();
    }

    // =====================================================================================
    // Transactional create (R5.8)
    // =====================================================================================

    @Test
    @DisplayName("A mid-create failure rolls the whole create back — no payout, no allocation, paid_amount unchanged (R5.8)")
    void transactionalCreateRollsBackEverythingOnFailure() {
        Fixture f = seedWorkerWithLines(1);
        authenticateAs(f.workerUserId, "ROLE_MANAGER");

        doThrow(new ForemenApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                "error.test.injected"))
                .when(allocationWriter).write(any(WorkerPayoutEntity.class), anyList(), anyMap());

        long payoutsBefore = workerPayoutDao.count();
        long allocationsBefore = allocationDao.count();

        assertThatThrownBy(() -> workerPayoutService.create(regularRequest(f.workerUserId, null)))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.test.injected"));

        assertThat(workerPayoutDao.count()).as("no payout row survives (R5.8)").isEqualTo(payoutsBefore);
        assertThat(allocationDao.count()).as("no allocation row survives (R5.8)").isEqualTo(allocationsBefore);
        assertThat(workReportRoomDao.findById(f.lineIds.get(0)).orElseThrow().getPaidAmount())
                .as("paid_amount stays 0 after the rolled-back create")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =====================================================================================
    // Worker-wide distribution + reversal (R5.3, R5.4, R6.2, R14.2)
    // =====================================================================================

    @Test
    @DisplayName("A payout fills the older line, partially pays the newer one across projects; delete reverses it")
    void distributesAcrossProjectsAndDeleteReverses() {
        Fixture f = seedWorkerWithLines(2);
        authenticateAs(f.workerUserId, "ROLE_FINANCIER");

        PayoutSuggestionDto preview = workerPayoutService.suggest(
                f.workerUserId, LocalDate.now().minusDays(10), LocalDate.now().minusDays(1));
        assertThat(preview.breakdown()).extracting(PayoutLineDto::workReportRoomId)
                .as("candidates span both projects, oldest report first (R4.2, R4.3)")
                .containsExactlyElementsOf(f.lineIds);
        BigDecimal first = preview.breakdown().get(0).remainder();
        BigDecimal second = preview.breakdown().get(1).remainder();
        assertThat(first.signum()).isPositive();
        BigDecimal half = second.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        WorkerPayoutDto created = workerPayoutService.create(regularRequest(f.workerUserId, first.add(half)));

        assertThat(paidOf(f.lineIds.get(0))).as("older line fully paid").isEqualByComparingTo(first);
        assertThat(paidOf(f.lineIds.get(1))).as("newer line partially paid (R5.4)").isEqualByComparingTo(half);
        assertThat(created.payoutDate()).isEqualTo(LocalDate.now());

        List<PayoutAllocationDto> allocations = workerPayoutService.allocations(created.id());
        assertThat(allocations).extracting(PayoutAllocationDto::lineId).containsExactlyElementsOf(f.lineIds);
        assertThat(allocations).extracting(PayoutAllocationDto::projectId)
                .as("one payout, two projects").doesNotHaveDuplicates();

        // R14.2 — a non-ADMIN reads company-wide (no project scoping).
        assertThat(workerPayoutService.list(new PayoutFilter(f.workerUserId, null, null, null)))
                .extracting(WorkerPayoutDto::id).containsExactly(created.id());

        workerPayoutService.delete(created.id());

        assertThat(paidOf(f.lineIds.get(0))).as("delete restores paid_amount (R6.2)").isEqualByComparingTo("0");
        assertThat(paidOf(f.lineIds.get(1))).isEqualByComparingTo("0");
        assertThat(allocationDao.findByPayoutId(created.id())).isEmpty();
        assertThat(workerPayoutDao.findById(created.id())).isEmpty();
    }

    // =====================================================================================
    // Audit (R6.4)
    // =====================================================================================

    @Test
    @DisplayName("A create, a mark-paid, and a delete each write a WorkerPayout audit row (R6.4)")
    void createPayDeleteEachWriteAnAuditRow() {
        Fixture f = seedWorkerWithLines(1);
        authenticateAs(f.workerUserId, "ROLE_MANAGER");

        WorkerPayoutDto created = workerPayoutService.create(regularRequest(f.workerUserId, null));
        List<AuditLogEntity> afterCreate = auditRowsFor(created.id());
        assertThat(afterCreate).as("a create writes exactly one audit row").hasSize(1);
        assertThat(afterCreate.get(0).getOperation()).isEqualTo("CREATE");
        assertThat(afterCreate.get(0).getEntityClass()).isEqualTo("WorkerPayoutEntity");

        workerPayoutService.markPaid(created.id());
        assertThat(auditRowsFor(created.id())).extracting(AuditLogEntity::getOperation)
                .contains("CREATE", "UPDATE");

        WorkerPayoutDto advance = workerPayoutService.create(advanceRequest(f.workerUserId));
        workerPayoutService.delete(advance.id());
        assertThat(auditRowsFor(advance.id())).extracting(AuditLogEntity::getOperation)
                .contains("DELETE");
    }

    // =====================================================================================
    // Regression smoke
    // =====================================================================================

    @Test
    @DisplayName("WorkerCostService + MarginMatrixService still load and cost a project whose line carries a paid amount")
    void regressionReusedServicesStillWork() {
        assertThat(applicationContext.getBeansOfType(WorkerCostService.class)).isNotEmpty();
        assertThat(applicationContext.getBeansOfType(
                com.foremen.service.execution.marginmatrix.MarginMatrixService.class)).isNotEmpty();

        Fixture f = seedWorkerWithLines(1);
        authenticateAs(f.workerUserId, "ROLE_MANAGER");

        WorkerPayoutDto created = workerPayoutService.create(regularRequest(f.workerUserId, null));
        assertThat(created.id()).isNotNull();
        assertThat(paidOf(f.lineIds.get(0))).as("the line now carries a positive paid amount").isPositive();

        var costModel = workerCostService.costForProject(f.projectIds.get(0));
        assertThat(costModel).isNotNull();
        assertThat(costModel.lines()).as("the APPROVED line is still costed").isNotEmpty();
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private void authenticateAs(Long userId, String authority) {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        String.valueOf(userId), "n/a", List.of(new SimpleGrantedAuthority(authority))));
    }

    private CreatePayoutRequest regularRequest(Long workerId, BigDecimal amount) {
        return new CreatePayoutRequest(
                workerId, null, LocalDate.now().minusDays(10), LocalDate.now().minusDays(1),
                PaymentChannel.BANK_TRANSFER, amount, "regular " + runId);
    }

    private CreatePayoutRequest advanceRequest(Long workerId) {
        return new CreatePayoutRequest(
                workerId, null, LocalDate.now(), LocalDate.now().plusDays(30),
                PaymentChannel.CASH, new BigDecimal("100.00"), "advance " + runId);
    }

    private BigDecimal paidOf(Long lineId) {
        return workReportRoomDao.findById(lineId).orElseThrow().getPaidAmount();
    }

    private List<AuditLogEntity> auditRowsFor(Long payoutId) {
        return auditLogDao.findByEntityClassAndEntityIdOrderByPerformedAtAsc("WorkerPayoutEntity", payoutId);
    }

    // =====================================================================================
    // Fixture building
    // =====================================================================================

    private static final class Fixture {
        Long workerUserId;
        List<Long> projectIds;
        List<Long> lineIds; // in distribution order (oldest report first)
    }

    /**
     * Seeds one WORKER user and {@code projects} projects, each with one APPROVED report (older
     * project first) carrying a single line with an available cost. Committed in one transaction.
     */
    private Fixture seedWorkerWithLines(int projects) {
        Fixture f = new Fixture();
        tx().executeWithoutResult(status -> {
            MeasurementUnitEntity m2 = persistUnit("m2");
            persistWorkerType("WT_BASE_" + runId + "_" + COUNTER.incrementAndGet(), "0.40", true);
            RoleEntity workerRole = workerRole();
            WorkCategoryEntity cat = persistCategory("CAT_" + runId + "_" + COUNTER.incrementAndGet());
            WorkItemEntity work = persistWorkItem("WI_" + runId + "_" + COUNTER.incrementAndGet(), cat, m2);
            UserEntity worker = persistUser("worker-" + runId + "-" + COUNTER.incrementAndGet(), workerRole);

            List<Long> projectIds = new java.util.ArrayList<>();
            List<Long> lineIds = new java.util.ArrayList<>();
            for (int i = 0; i < projects; i++) {
                ProjectEntity project = persistProject("Payroll Service IT",
                        i == 0 ? ProjectStatus.ACTIVE : ProjectStatus.COMPLETED);
                LocalDate performedFrom = LocalDate.now().minusDays(8 - i * 3L);
                WorkReportEntity report = persistApprovedReport(project, work, worker, "100.00",
                        performedFrom, performedFrom.plusDays(1));
                WorkReportRoomEntity line = persistReportLine(report, project, "2.000");
                projectIds.add(project.getId());
                lineIds.add(line.getId());
            }
            f.workerUserId = worker.getId();
            f.projectIds = projectIds;
            f.lineIds = lineIds;
        });
        return f;
    }

    /** The shared WORKER role (find-or-create; not deleted on clean-up). */
    private RoleEntity workerRole() {
        return roleDao.findByCode("WORKER").orElseGet(() -> {
            RoleEntity role = new RoleEntity();
            role.setCode("WORKER");
            role.setNameRU("Работник");
            role.setNamePL("Pracownik");
            role.setSystem(true);
            return roleDao.save(role);
        });
    }

    private MeasurementUnitEntity persistUnit(String code) {
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
        return measurementUnitDao.save(unit);
    }

    private WorkerTypeEntity persistWorkerType(String code, String tierPct, boolean base) {
        WorkerTypeEntity entity = new WorkerTypeEntity();
        entity.setCode(code);
        entity.setTierPct(new BigDecimal(tierPct));
        entity.setBase(base);
        entity.setOrderNo(0);
        entity.setNameRU("Базовый " + code);
        entity.setNamePL("Bazowy " + code);
        entity.setActive(true);
        return entityManager.merge(entity);
    }

    private WorkCategoryEntity persistCategory(String code) {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setCode(code);
        category.setOrderNo(1);
        category.setNameRU("Категория " + code);
        category.setNamePL("Kategoria " + code);
        category.setActive(true);
        return workCategoryDao.save(category);
    }

    private WorkItemEntity persistWorkItem(String code, WorkCategoryEntity category, MeasurementUnitEntity unit) {
        WorkItemEntity item = new WorkItemEntity();
        item.setWorkCategory(category);
        item.setUnit(unit);
        item.setNameRU("Работа " + code);
        item.setNamePL("Praca " + code);
        item.setCode(code);
        item.setActive(true);
        return workItemDao.save(item);
    }

    private ProjectEntity persistProject(String label, ProjectStatus projectStatus) {
        ProjectEntity project = new ProjectEntity();
        project.setName(label + " " + runId + "-" + COUNTER.incrementAndGet());
        project.setStatus(projectStatus);
        return projectDao.save(project);
    }

    private UserEntity persistUser(String key, RoleEntity role) {
        UserEntity user = new UserEntity();
        user.setName("Worker " + key);
        user.setEmail(key + "@example.com");
        user.setRole(role);
        user.setActive(true);
        user.setLocale(java.util.Locale.forLanguageTag("pl"));
        return userDao.save(user);
    }

    private WorkReportEntity persistApprovedReport(ProjectEntity project, WorkItemEntity workItem,
                                                   UserEntity worker, String clientUnitPrice,
                                                   LocalDate performedFrom, LocalDate performedTo) {
        WorkReportEntity report = new WorkReportEntity();
        report.setProject(project);
        report.setWorkItem(workItem);
        report.setAuthor(worker);
        report.setWorker(worker);
        report.setPerformedFrom(performedFrom);
        report.setPerformedTo(performedTo);
        report.setStatus(WorkReportStatus.APPROVED);
        report.setClientUnitPrice(new BigDecimal(clientUnitPrice));
        report.setTotalQuantity(new BigDecimal("2.000"));
        report.setRevenueValue(new BigDecimal(clientUnitPrice).multiply(new BigDecimal("2")));
        return workReportDao.save(report);
    }

    private WorkReportRoomEntity persistReportLine(WorkReportEntity report, ProjectEntity project,
                                                   String quantity) {
        com.foremen.dao.model.RoomTypeEntity roomType = getOrCreateRoomType();
        com.foremen.dao.model.RoomEntity room = new com.foremen.dao.model.RoomEntity();
        room.setProject(project);
        room.setRoomType(roomType);
        room.setLabel("Room " + runId + "-" + COUNTER.incrementAndGet());
        entityManager.persist(room);

        WorkReportRoomEntity line = new WorkReportRoomEntity();
        line.setReport(report);
        line.setRoom(room);
        line.setQuantity(new BigDecimal(quantity));
        line.setInputMode(WorkReportInputMode.ABSOLUTE);
        return workReportRoomDao.save(line);
    }

    private com.foremen.dao.model.RoomTypeEntity getOrCreateRoomType() {
        List<com.foremen.dao.model.RoomTypeEntity> existing = entityManager
                .createQuery("select rt from RoomTypeEntity rt where rt.code = :c",
                        com.foremen.dao.model.RoomTypeEntity.class)
                .setParameter("c", "ROOMTYPE_" + runId)
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        com.foremen.dao.model.RoomTypeEntity roomType = new com.foremen.dao.model.RoomTypeEntity();
        roomType.setCode("ROOMTYPE_" + runId);
        roomType.setNameRU("Комната " + runId);
        roomType.setNamePL("Pokój " + runId);
        roomType.setActive(true);
        entityManager.persist(roomType);
        return roomType;
    }
}
