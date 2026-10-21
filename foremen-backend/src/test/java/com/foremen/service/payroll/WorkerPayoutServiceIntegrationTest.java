package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
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
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkReportDao;
import com.foremen.dao.WorkReportRoomDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
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
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.payroll.dto.CreatePayoutRequest;
import com.foremen.service.payroll.dto.PayrollFilter;
import com.foremen.service.payroll.dto.PayrollView;
import com.foremen.service.payroll.dto.WorkerPayoutDto;
import com.foremen.service.workercost.WorkerCostService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-06-11 task 8.2 — payroll service-level integration tests against a real PostgreSQL
 * (Testcontainers), driving the Spring-wired {@link WorkerPayoutService} end to end over genuine
 * transactions, the {@code ProjectScopedService} access gate, and the existing audit mechanism
 * (design §Testing strategy "Integration tests"; R4.7, R10.5, R12.1, R12.2).
 *
 * <p>Harness mirrors {@code WorkerCostServiceIntegrationTest} / {@code ProjectActionNoPartialMutationIntegrationTest}:
 * {@code @SpringBootTest(MOCK)} + {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")}
 * (Liquibase disabled, Hibernate {@code create-drop}). Every fixture row carries a unique per-run id
 * and the caller is authenticated by setting the {@code SecurityContext} principal to the member's
 * numeric id (as the JWT layer does), with the member's project wired into {@link ProjectAccessCache}
 * via a real {@code project_members} row, so the service's 404 project gate reads its allowed set
 * exactly as production.
 *
 * <p>Cases:
 * <ul>
 *   <li><b>Transactional create (R4.7).</b> A regular create with a linkable APPROVED line has its
 *       {@link ReportLineLinker#link} step forced to throw (via a {@link MockitoSpyBean}); the whole
 *       create rolls back — <b>no payout row persists, the report line's {@code paidByPayout} FK
 *       stays null, and the worker's remaining advance credit does not drift</b>.</li>
 *   <li><b>Project scope (R10.5).</b> A member of project A never sees project B's payouts on a read,
 *       and a {@code list}/{@code suggest} against project B is denied with a 404.</li>
 *   <li><b>Audit (R12.1).</b> A create, a mark-paid, and a delete each write an audit row for
 *       {@code WorkerPayoutEntity} via the existing mechanism.</li>
 *   <li><b>Stage reachability (R10.1, surface).</b> A payout is creatable+listable on an
 *       {@code ACTIVE} and a {@code COMPLETED} project (the backend payroll surface is reachable);
 *       the {@code DRAFT}-hides-the-tab gate is a frontend tab-resolver concern (task 10.2).</li>
 *   <li><b>Regression smoke (R12.2).</b> The FOR-06-10 {@code WorkerCostService} and the FOR-06-06
 *       {@code MarginMatrixService} beans still load, and {@code WorkerCostService.costForProject}
 *       still derives cost over a project whose report line now carries the new {@code paidByPayout}
 *       FK — payroll added the FK + the {@code PAYROLL} resource without altering their behaviour.</li>
 * </ul>
 *
 * <p>Repeatability: {@link #cleanUp()} removes every row this test created (deepest-FK-first) and
 * clears the security context + the project-access cache after each test.
 *
 * <p>Validates: Requirements 4.7, 10.1, 10.5, 12.1, 12.2
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
@Tag("Feature: FOR-06-11-payroll, task 8.2: service integration — transactional/scope/audit/regression")
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

    /** Spy on the sole paid-by-FK mutator so the transactional test can force a mid-create failure. */
    @MockitoSpyBean
    private ReportLineLinker reportLineLinker;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WorkerCostService workerCostService;

    @Autowired
    private ProjectAccessCache projectAccessCache;

    @Autowired
    private AuditLogDao auditLogDao;

    @Autowired
    private WorkerPayoutDao workerPayoutDao;
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
    private ProjectMemberDao projectMemberDao;
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
            // Delete deepest-first in FK order. work_report_rooms references BOTH work_reports
            // (report_id) AND worker_payouts (paid_by_payout_id), so the report lines must go before
            // either parent; worker_payouts references projects, so payouts precede projects; rooms
            // reference projects, so rooms precede projects too.
            exec("delete from WorkReportRoomEntity rr where rr.report.id in "
                    + "(select r.id from WorkReportEntity r where r.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p))");
            exec("delete from WorkerPayoutEntity p where p.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from WorkReportEntity r where r.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from RoomEntity rm where rm.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from ProjectMemberEntity m where m.projectId in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from ProjectEntity pr where pr.name like :p");
            exec("delete from UserEntity u where u.email like :p");
            exec("delete from WorkItemEntity wi where wi.id in "
                    + "(select w.id from WorkItemEntity w where w.workCategory.code like :p)");
            exec("delete from WorkCategoryEntity wc where wc.code like :p");
            exec("delete from WorkerTypeEntity w where w.code like :p");
            exec("delete from RoomTypeEntity rt where rt.code like :p");
            exec("delete from RoleEntity r where r.code like :p");
        });
    }

    private void exec(String jpql) {
        entityManager.createQuery(jpql).setParameter("p", "%" + runId + "%").executeUpdate();
    }

    // =====================================================================================
    // Transactional create (R4.7)
    // =====================================================================================

    @Test
    @DisplayName("A mid-create failure rolls the whole create back — no payout, no FK change, no credit drift (R4.7)")
    void transactionalCreateRollsBackEverythingOnFailure() {
        Fixture f = seedProjectWithApprovedLine(ProjectStatus.ACTIVE);
        authenticate(f.memberUserId);

        // Force the link step (invoked only for a regular payout, after the payout row is flushed)
        // to throw, simulating a mid-create failure strictly after the row + before commit.
        doThrow(new ForemenApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                "error.test.injected"))
                .when(reportLineLinker).link(any(WorkerPayoutEntity.class), any());

        long payoutsBefore = workerPayoutDao.count();

        assertThatThrownBy(() -> workerPayoutService.create(f.projectId, regularRequest(f.workerUserId)))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.test.injected"));

        // No payout row persisted — the flushed row was rolled back with the transaction.
        assertThat(workerPayoutDao.count())
                .as("the payout row must be rolled back on a mid-create failure (R4.7)")
                .isEqualTo(payoutsBefore);
        assertThat(payoutsForProject(f.projectId))
                .as("no payout exists for the project after the rolled-back create")
                .isEmpty();

        // The report line's paid-by FK is still null — the line was never linked (R4.7).
        WorkReportRoomEntity line = workReportRoomDao.findById(f.reportLineId).orElseThrow();
        assertThat(line.getPaidByPayout())
                .as("the report line's paidByPayout FK must stay null after the rolled-back create")
                .isNull();

        // The worker's remaining advance credit did not drift (no payout rows ⇒ balance 0).
        PayrollView view = readAsMember(f);
        assertThat(remainingCreditFor(view, f.workerUserId))
                .as("the worker's remaining advance credit must not drift after the rolled-back create")
                .isEqualByComparingTo("0");
    }

    // =====================================================================================
    // Project scope (R10.5)
    // =====================================================================================

    @Test
    @DisplayName("A read never returns another project's payouts; a read of an out-of-scope project is 404 (R10.5)")
    void readsAreConfinedToTheResolvedProject() {
        Fixture a = seedProjectWithApprovedLine(ProjectStatus.ACTIVE);
        Fixture b = seedProjectWithApprovedLine(ProjectStatus.ACTIVE);

        // The caller is a member of project A only.
        authenticate(a.memberUserId);

        // Create a payout on project A (as the member) so A has a row to (not) leak.
        WorkerPayoutDto created = workerPayoutService.create(a.projectId, regularRequest(a.workerUserId));
        assertThat(created.id()).isNotNull();

        // A read of project A returns only A's payouts — never B's.
        PayrollView aView = workerPayoutService.list(a.projectId, new PayrollFilter(null, null));
        assertThat(aView.projectId()).isEqualTo(a.projectId);
        assertThat(aView.payouts())
                .as("project A's read model carries only its own payout")
                .extracting(WorkerPayoutDto::id)
                .containsExactly(created.id());

        // A read of project B (the caller is NOT a member) is denied with a 404 — out-of-scope is
        // indistinguishable from missing (R10.5).
        assertThatThrownBy(() -> workerPayoutService.list(b.projectId, new PayrollFilter(null, null)))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));

        // A suggest against the out-of-scope project is likewise confined/denied (write-path gate).
        assertThatThrownBy(() -> workerPayoutService.suggest(
                b.projectId, b.workerUserId, LocalDate.now().minusDays(10), LocalDate.now().minusDays(1)))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
    }

    // =====================================================================================
    // Audit (R12.1)
    // =====================================================================================

    @Test
    @DisplayName("A create, a mark-paid, and a delete each write a WorkerPayout audit row (R12.1)")
    void createPayDeleteEachWriteAnAuditRow() {
        Fixture f = seedProjectWithApprovedLine(ProjectStatus.ACTIVE);
        authenticate(f.memberUserId);

        // CREATE → one CREATE audit row.
        WorkerPayoutDto created = workerPayoutService.create(f.projectId, regularRequest(f.workerUserId));
        List<AuditLogEntity> afterCreate = auditRowsFor(created.id());
        assertThat(afterCreate)
                .as("a create must write exactly one WorkerPayout audit row (R12.1)")
                .hasSize(1);
        assertThat(afterCreate.get(0).getOperation()).isEqualTo("CREATE");
        assertThat(afterCreate.get(0).getEntityClass()).isEqualTo("WorkerPayoutEntity");

        // MARK PAID → a second (UPDATE) audit row.
        workerPayoutService.markPaid(created.id());
        assertThat(auditRowsFor(created.id()))
                .as("a mark-paid must add an UPDATE audit row (R12.1)")
                .extracting(AuditLogEntity::getOperation)
                .contains("CREATE", "UPDATE");

        // A PAID payout cannot be deleted; create a fresh PLANNED payout to exercise DELETE audit.
        WorkerPayoutDto advance = workerPayoutService.create(f.projectId, advanceRequest(f.workerUserId));
        workerPayoutService.delete(advance.id());
        assertThat(auditRowsFor(advance.id()))
                .as("a delete must write a DELETE audit row (R12.1)")
                .extracting(AuditLogEntity::getOperation)
                .contains("DELETE");
    }

    // =====================================================================================
    // Stage reachability (R10.1, backend surface)
    // =====================================================================================

    @Test
    @DisplayName("The payroll surface is reachable on ACTIVE and COMPLETED projects (R10.1, surface)")
    void payrollSurfaceReachableOnActiveAndCompleted() {
        Fixture active = seedProjectWithApprovedLine(ProjectStatus.ACTIVE);
        authenticate(active.memberUserId);
        WorkerPayoutDto onActive = workerPayoutService.create(active.projectId, regularRequest(active.workerUserId));
        assertThat(onActive.id()).as("a payout is creatable on an ACTIVE project").isNotNull();
        assertThat(workerPayoutService.list(active.projectId, new PayrollFilter(null, null)).payouts())
                .as("the payroll list is reachable on an ACTIVE project").isNotEmpty();

        Fixture completed = seedProjectWithApprovedLine(ProjectStatus.COMPLETED);
        authenticate(completed.memberUserId);
        WorkerPayoutDto onCompleted =
                workerPayoutService.create(completed.projectId, regularRequest(completed.workerUserId));
        assertThat(onCompleted.id()).as("a payout is creatable on a COMPLETED project").isNotNull();
        assertThat(workerPayoutService.list(completed.projectId, new PayrollFilter(null, null)).payouts())
                .as("the payroll list is reachable on a COMPLETED project").isNotEmpty();
        // The DRAFT-hides-the-tab stage gate (P10) is enforced at the frontend tab resolver
        // (workspaceTabs `stageOf` + `resolveWorkspaceTabs`), covered by task 10.2 — the backend
        // payroll surface itself is not project-status-gated.
    }

    // =====================================================================================
    // Regression smoke (R12.2)
    // =====================================================================================

    @Test
    @DisplayName("FOR-06-10 WorkerCostService + FOR-06-06 MarginMatrixService still load and derive "
            + "cost over a project whose line now carries the paidByPayout FK (regression R12.2)")
    void regressionReusedServicesStillWork() {
        // The reused beans still wire (the new FK/resource did not break their context wiring).
        assertThat(applicationContext.containsBean("workerCostService")
                || !applicationContext.getBeansOfType(WorkerCostService.class).isEmpty())
                .as("the FOR-06-10 WorkerCostService bean still loads").isTrue();
        assertThat(applicationContext.getBeansOfType(
                com.foremen.service.execution.marginmatrix.MarginMatrixService.class))
                .as("the FOR-06-06 MarginMatrixService bean still loads").isNotEmpty();

        Fixture f = seedProjectWithApprovedLine(ProjectStatus.ACTIVE);
        authenticate(f.memberUserId);

        // Create a regular payout so the covered APPROVED line gets its paidByPayout FK set.
        WorkerPayoutDto created = workerPayoutService.create(f.projectId, regularRequest(f.workerUserId));
        assertThat(created.id()).isNotNull();
        WorkReportRoomEntity linked = workReportRoomDao.findById(f.reportLineId).orElseThrow();
        assertThat(linked.getPaidByPayout())
                .as("the regular payout linked the covered line (sets paidByPayout)").isNotNull();

        // FOR-06-10 WorkerCostService still derives the project's cost read model — it reads
        // APPROVED reports and the worker-tier/cost core, ignoring the payroll paid-by FK entirely.
        var costModel = workerCostService.costForProject(f.projectId);
        assertThat(costModel).as("WorkerCostService still returns a read model after payroll linkage").isNotNull();
        assertThat(costModel.lines())
                .as("the APPROVED line is still costed by FOR-06-10 regardless of its paidByPayout FK "
                        + "— the FOR-06-01 status machine is unchanged (R12.2)")
                .isNotEmpty();
    }

    // =====================================================================================
    // Helpers: auth, requests, reads
    // =====================================================================================

    private void authenticate(Long userId) {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        String.valueOf(userId), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
    }

    private CreatePayoutRequest regularRequest(Long workerId) {
        // A non-advance period (periodTo strictly before today) with amount defaulting to suggested.
        return new CreatePayoutRequest(
                workerId, LocalDate.now().minusDays(10), LocalDate.now().minusDays(1),
                PaymentChannel.BANK_TRANSFER, null, "regular " + runId);
    }

    private CreatePayoutRequest advanceRequest(Long workerId) {
        // A forward-looking period (periodTo after today) ⇒ derived advance; a fixed amount.
        return new CreatePayoutRequest(
                workerId, LocalDate.now(), LocalDate.now().plusDays(30),
                PaymentChannel.CASH, new BigDecimal("100.00"), "advance " + runId);
    }

    private PayrollView readAsMember(Fixture f) {
        return workerPayoutService.list(f.projectId, new PayrollFilter(null, null));
    }

    private BigDecimal remainingCreditFor(PayrollView view, Long workerId) {
        return view.workers().stream()
                .filter(w -> workerId.equals(w.workerId()))
                .map(w -> w.remainingAdvanceCredit())
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private List<WorkerPayoutEntity> payoutsForProject(Long projectId) {
        return workerPayoutDao.findByProjectWithWorker(projectId);
    }

    private List<AuditLogEntity> auditRowsFor(Long payoutId) {
        return auditLogDao.findByEntityClassAndEntityIdOrderByPerformedAtAsc("WorkerPayoutEntity", payoutId);
    }

    // =====================================================================================
    // Fixture building
    // =====================================================================================

    private static final class Fixture {
        Long projectId;
        Long workerUserId;   // the worker being paid (an ACTIVE member)
        Long memberUserId;   // the authenticated caller (same user — an ACTIVE member)
        Long reportLineId;   // the APPROVED work_report_rooms line (available cost, linkable)
    }

    /**
     * Seeds a self-contained project graph in one committed transaction: a base WorkerType, a role,
     * a unit + category + work item, an ACTIVE member/worker, and one APPROVED work report with a
     * single room line carrying an available (positive-rate) cost so the regular-payout gross is
     * non-zero and the line is linkable. The member's project is pushed into the ProjectAccessCache
     * so the service's project gate grants it.
     */
    private Fixture seedProjectWithApprovedLine(ProjectStatus projectStatus) {
        Fixture f = new Fixture();
        tx().executeWithoutResult(status -> {
            MeasurementUnitEntity m2 = persistUnit("m2");
            WorkerTypeEntity base = persistWorkerType("WT_BASE_" + runId + "_" + COUNTER.incrementAndGet(),
                    "0.40", true);
            RoleEntity role = persistRole("PAY_ROLE_" + runId + "_" + COUNTER.incrementAndGet());
            WorkCategoryEntity cat = persistCategory("CAT_" + runId + "_" + COUNTER.incrementAndGet());
            WorkItemEntity work = persistWorkItem("WI_" + runId + "_" + COUNTER.incrementAndGet(), cat, m2);

            ProjectEntity project = persistProject("Payroll Service IT", projectStatus);
            UserEntity worker = persistUser("worker-" + runId + "-" + COUNTER.incrementAndGet(), role);
            persistMember(worker, project.getId(), role, base, AssignmentStatus.ACTIVE);

            WorkReportEntity report = persistApprovedReport(project, work, worker, "100.00");
            WorkReportRoomEntity line = persistReportLine(report, project, "2.000");

            f.projectId = project.getId();
            f.workerUserId = worker.getId();
            f.memberUserId = worker.getId();
            f.reportLineId = line.getId();
        });
        // Grant the caller access to its project exactly as production resolves it.
        projectAccessCache.invalidate(f.memberUserId);
        return f;
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

    private RoleEntity persistRole(String code) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        role.setNameRU("Роль " + runId);
        role.setNamePL("Rola " + runId);
        role.setSystem(false);
        return roleDao.save(role);
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

    private void persistMember(UserEntity user, Long projectId, RoleEntity role, WorkerTypeEntity workerType,
                               AssignmentStatus assignmentStatus) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(projectId);
        member.setProjectRole(role);
        member.setWorkerType(workerType);
        member.setAssignmentStatus(assignmentStatus);
        projectMemberDao.save(member);
    }

    private WorkReportEntity persistApprovedReport(ProjectEntity project, WorkItemEntity workItem,
                                                   UserEntity worker, String clientUnitPrice) {
        WorkReportEntity report = new WorkReportEntity();
        report.setProject(project);
        report.setWorkItem(workItem);
        report.setAuthor(worker);
        report.setWorker(worker);
        report.setPerformedFrom(LocalDate.now().minusDays(8));
        report.setPerformedTo(LocalDate.now().minusDays(2));
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
