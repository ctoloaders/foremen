package com.foremen.service.workercost;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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

import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkReportDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportStatus;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.dao.MeasurementUnitDao;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-06-10 task 5.1 — Spring integration test for {@link WorkerCostService#costForProject(Long)}, the
 * worker-cost read model, over a <strong>directly seeded</strong> project graph against a real
 * PostgreSQL (Testcontainers). It drives the real Spring-wired service end-to-end through the APPROVED
 * work-report read, the worker-tier resolution via {@code ProjectMember.workerType} (active / null /
 * inactive / missing), the active {@code WorkerType} dictionary, and the shared
 * {@link WorkerCostDerivation} cost core.
 *
 * <p>Harness mirrors {@code MarginMatrixServiceIntegrationTest}:
 * {@code @SpringBootTest(MOCK)} + {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")}
 * where Liquibase is disabled and Hibernate {@code create-drop} builds the schema — so the WorkerType
 * / role seeds do <em>not</em> pre-populate; this test seeds its own base + firm + inactive tiers and
 * its own roles/users. Reports are seeded directly (no live work-reporting dependency).
 *
 * <p><b>What is asserted (R1.1, R3.1, R3.2, R3.3, R3.4, R5.3):</b>
 * <ul>
 *   <li><b>Per-report resolved tier + Cost_Value.</b> An active-firm-type worker costs at the firm
 *       tier ({@code 100 → Base_Cost 40 × 1.65 = 66.00}, ×2 qty = 132.00); an Uncategorized worker
 *       (null type), an inactive-type worker, and a no-membership worker all fall back to the base
 *       tier ({@code baseTierFallback = true}, {@code 40.00} unit, ×qty).</li>
 *   <li><b>Unavailable-rate line.</b> A null/zero-rate report yields a null unit cost + null
 *       Cost_Value and is <em>excluded</em> from every aggregate (R3.4).</li>
 *   <li><b>Approved-only.</b> A SUBMITTED report never appears in the read model (R3.3).</li>
 *   <li><b>Aggregates.</b> The per-worker and per-work-item sums equal the sum of the available-cost
 *       approved lines (R3.2).</li>
 *   <li><b>Project scope (R5.3).</b> A second project's reports never leak into the first project's
 *       read model.</li>
 * </ul>
 *
 * <p><b>Repeatability.</b> Every fixture row carries a unique per-run id (UUID substring) and
 * {@link #cleanUp()} removes every row this test created (deepest-FK-first) after each test, so the
 * suite re-runs without manual DB cleanup.
 *
 * <p>Validates: Requirements 1.1, 3.1, 3.2, 3.3, 3.4, 5.3
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
@Tag("Feature: FOR-06-10-worker-cost-rates, task 5.1: costForProject integration + project scope")
class WorkerCostServiceIntegrationTest {

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
    private WorkerCostService workerCostService;

    @Autowired
    private ProjectDao projectDao;
    @Autowired
    private MeasurementUnitDao measurementUnitDao;
    @Autowired
    private WorkCategoryDao workCategoryDao;
    @Autowired
    private WorkItemDao workItemDao;
    @Autowired
    private WorkerTypeDao workerTypeDao;
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

    /** Unique run id so fixture codes/names never collide with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        tx().executeWithoutResult(status -> {
            exec("delete from WorkReportEntity r where r.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from ProjectMemberEntity m where m.projectId in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from ProjectEntity pr where pr.name like :p");
            exec("delete from UserEntity u where u.email like :p");
            exec("delete from WorkItemEntity wi where wi.id in "
                    + "(select w.id from WorkItemEntity w where w.workCategory.code like :p)");
            exec("delete from WorkCategoryEntity wc where wc.code like :p");
            exec("delete from WorkerTypeEntity w where w.code like :p");
            exec("delete from RoleEntity r where r.code like :p");
            // The shared "m2" unit fixture is intentionally NOT deleted (reused across runs).
        });
    }

    private void exec(String jpql) {
        entityManager.createQuery(jpql).setParameter("p", "%" + runId + "%").executeUpdate();
    }

    // =====================================================================================
    // Tests
    // =====================================================================================

    @Test
    @DisplayName("costForProject: per-report resolved tier + Cost_Value, base-tier fallback for "
            + "Uncategorized/inactive/missing, unavailable-rate excluded, non-APPROVED excluded, "
            + "and the per-worker/per-work-item aggregates (R1.1, R3.1-R3.4)")
    void costForProjectOverSeededProject() {
        Fixture f = seedFixture();

        WorkerCostReadModelDto dto = workerCostService.costForProject(f.projectId);

        assertThat(dto).as("the read model must be non-null (R1.1)").isNotNull();
        assertThat(dto.projectId()).isEqualTo(f.projectId);

        // Five APPROVED reports become five lines; the SUBMITTED report is excluded (R3.3).
        assertThat(dto.lines())
                .as("only APPROVED reports produce lines — the SUBMITTED report is excluded (R3.3)")
                .hasSize(5);
        assertThat(dto.lines()).extracting(WorkerCostLineDto::reportId)
                .doesNotContain(f.submittedReportId);

        // --- firm worker: Base_Cost 40 × 1.65 = 66.00 unit; ×2 qty = 132.00 ---------------------
        WorkerCostLineDto firm = lineForReport(dto, f.firmReportId);
        assertThat(firm.baseTierFallback()).as("an active firm-type worker does not fall back").isFalse();
        assertThat(firm.workerTypeId()).isEqualTo(f.firmTypeId);
        assertThat(firm.workItemRate()).isEqualByComparingTo("100.00");
        assertThat(firm.unitServiceCost()).isEqualByComparingTo("66.00");
        assertThat(firm.costValue()).isEqualByComparingTo("132.00");

        // --- Uncategorized worker (null type): base-tier fallback, 40.00 unit, ×1 = 40.00 -------
        WorkerCostLineDto uncategorized = lineForReport(dto, f.uncategorizedReportId);
        assertBaseFallback(uncategorized, "40.00", "40.00");

        // --- inactive-type worker: base-tier fallback (R2.2), 40.00 unit × 3 = 120.00 -----------
        WorkerCostLineDto inactive = lineForReport(dto, f.inactiveReportId);
        assertBaseFallback(inactive, "40.00", "120.00");

        // --- no-membership worker: base-tier fallback, 40.00 unit × 1 = 40.00 -------------------
        WorkerCostLineDto noMember = lineForReport(dto, f.noMemberReportId);
        assertBaseFallback(noMember, "40.00", "40.00");

        // --- unavailable-rate line: null unit + null Cost_Value, excluded from sums (R3.4) ------
        WorkerCostLineDto unavailable = lineForReport(dto, f.unavailableReportId);
        assertThat(unavailable.unitServiceCost()).as("a zero-rate report has no unit cost (R1.5)").isNull();
        assertThat(unavailable.costValue()).as("a zero-rate report has no Cost_Value (R1.5)").isNull();

        // --- per-worker aggregate: only the available-cost reports contribute (R3.2, R3.4) ------
        // firm 132 ; uncategorized 40 ; inactive 120 ; noMember 40 ; the zero-rate firm-worker line
        // (same worker as firm) adds nothing. So the firm worker's bucket is exactly 132.00.
        assertThat(dto.byWorker())
                .as("the per-worker aggregate excludes the unavailable-rate line (R3.4)")
                .anySatisfy(w -> {
                    assertThat(w.workerId()).isEqualTo(f.firmWorkerId);
                    assertThat(w.reportCount()).as("only the firm worker's available-cost report counts").isEqualTo(1);
                    assertThat(w.totalCost()).isEqualByComparingTo("132.00");
                })
                .anySatisfy(w -> {
                    assertThat(w.workerId()).isEqualTo(f.inactiveWorkerId);
                    assertThat(w.totalCost()).isEqualByComparingTo("120.00");
                });

        // --- per-work-item aggregate: all available lines are on the single work item -----------
        // 132 + 40 + 120 + 40 = 332.00 (the unavailable line excluded).
        assertThat(dto.byWorkItem())
                .as("the per-work-item aggregate sums the available-cost approved lines (R3.2)")
                .singleElement()
                .satisfies(wi -> {
                    assertThat(wi.workItemId()).isEqualTo(f.workItemId);
                    assertThat(wi.reportCount()).isEqualTo(4);
                    assertThat(wi.totalCost()).isEqualByComparingTo("332.00");
                });
    }

    @Test
    @DisplayName("costForProject never returns another project's reports — project scope (R5.3)")
    void costForProjectIsProjectScoped() {
        Fixture f = seedFixture();
        Long otherProjectId = seedOtherProject(f);

        WorkerCostReadModelDto dto = workerCostService.costForProject(f.projectId);

        assertThat(dto.lines()).extracting(WorkerCostLineDto::reportId)
                .as("the first project's read model must not contain the other project's report (R5.3)")
                .doesNotContain(f.otherReportId);
        assertThat(dto.lines()).as("the first project still has its five approved lines").hasSize(5);

        // The other project's read model carries only its own single line.
        WorkerCostReadModelDto otherDto = workerCostService.costForProject(otherProjectId);
        assertThat(otherDto.lines())
                .as("the other project's read model carries only its own line (R5.3)")
                .singleElement()
                .satisfies(l -> assertThat(l.reportId()).isEqualTo(f.otherReportId));
    }

    // =====================================================================================
    // Assertion helpers
    // =====================================================================================

    private void assertBaseFallback(WorkerCostLineDto line, String expectedUnit, String expectedCost) {
        assertThat(line.baseTierFallback())
                .as("Uncategorized/inactive/missing worker falls back to the base tier (R2.2)")
                .isTrue();
        assertThat(line.workerTypeId()).as("a base-tier fallback carries a null worker type id").isNull();
        assertThat(line.unitServiceCost()).isEqualByComparingTo(expectedUnit);
        assertThat(line.costValue()).isEqualByComparingTo(expectedCost);
    }

    private WorkerCostLineDto lineForReport(WorkerCostReadModelDto dto, Long reportId) {
        return dto.lines().stream()
                .filter(l -> reportId.equals(l.reportId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line for report " + reportId));
    }

    // =====================================================================================
    // Fixture building
    // =====================================================================================

    private static final class Fixture {
        Long projectId;
        Long workItemId;
        Long firmTypeId;
        // report ids
        Long firmReportId;
        Long uncategorizedReportId;
        Long inactiveReportId;
        Long noMemberReportId;
        Long unavailableReportId;
        Long submittedReportId;
        // worker ids
        Long firmWorkerId;
        Long inactiveWorkerId;
        // shared reference data reused by the second project
        Long m2UnitId;
        Long workerRoleId;
        // second project
        Long otherReportId;
    }

    /**
     * Seeds a self-contained project graph in one transaction: a base + firm + inactive WorkerType
     * tier, an m2 unit, one work category + one work item, four WORKER members (firm / Uncategorized /
     * inactive-type / and a fifth worker with no membership), and six reports on the work item — five
     * APPROVED (firm, Uncategorized, inactive-type, no-membership, and a zero-rate firm-worker report)
     * plus one SUBMITTED (must be excluded, R3.3).
     */
    private Fixture seedFixture() {
        Fixture f = new Fixture();
        tx().executeWithoutResult(status -> {
            MeasurementUnitEntity m2 = persistUnit("m2");
            WorkerTypeEntity base = persistWorkerType("WT_BASE_" + runId, "0.40", true, 0, true, "Базовый", "Bazowy");
            WorkerTypeEntity firm = persistWorkerType("WT_FIRM_" + runId, "0.65", false, 3, true, "Фирма", "Firma");
            WorkerTypeEntity dead = persistWorkerType("WT_DEAD_" + runId, "0.10", false, 2, false, "Старый", "Stary");
            RoleEntity workerRole = persistRole("WORKER_" + runId);

            WorkCategoryEntity cat = persistCategory("CAT_" + runId, 1);
            WorkItemEntity work = persistWorkItem("WI_" + runId, cat, m2);

            ProjectEntity project = persistProject("Worker Cost IT");
            EntityRefs refs = new EntityRefs();

            UserEntity firmWorker = persistUser("firm-" + runId, "Firma Sp.", workerRole);
            UserEntity uncategorizedWorker = persistUser("unc-" + runId, "Jan", workerRole);
            UserEntity inactiveWorker = persistUser("ina-" + runId, "Piotr", workerRole);
            UserEntity noMemberWorker = persistUser("nom-" + runId, "Anna", workerRole);

            persistMember(firmWorker, project.getId(), workerRole, firm, AssignmentStatus.ACTIVE);
            persistMember(uncategorizedWorker, project.getId(), workerRole, null, AssignmentStatus.ACTIVE);
            persistMember(inactiveWorker, project.getId(), workerRole, dead, AssignmentStatus.ACTIVE);
            // noMemberWorker has NO ProjectMember row on this project at all (missing membership).

            refs.firmReportId = persistReport(project, work, firmWorker,
                    WorkReportStatus.APPROVED, "100.00", "2").getId();
            refs.uncategorizedReportId = persistReport(project, work, uncategorizedWorker,
                    WorkReportStatus.APPROVED, "100.00", "1").getId();
            refs.inactiveReportId = persistReport(project, work, inactiveWorker,
                    WorkReportStatus.APPROVED, "100.00", "3").getId();
            refs.noMemberReportId = persistReport(project, work, noMemberWorker,
                    WorkReportStatus.APPROVED, "100.00", "1").getId();
            // A zero-rate report (unavailable cost) by the firm worker — excluded from sums (R3.4).
            refs.unavailableReportId = persistReport(project, work, firmWorker,
                    WorkReportStatus.APPROVED, "0.00", "5").getId();
            // A SUBMITTED report — must be excluded from the read model entirely (R3.3).
            refs.submittedReportId = persistReport(project, work, firmWorker,
                    WorkReportStatus.SUBMITTED, "100.00", "9").getId();

            f.projectId = project.getId();
            f.workItemId = work.getId();
            f.firmTypeId = firm.getId();
            f.firmReportId = refs.firmReportId;
            f.uncategorizedReportId = refs.uncategorizedReportId;
            f.inactiveReportId = refs.inactiveReportId;
            f.noMemberReportId = refs.noMemberReportId;
            f.unavailableReportId = refs.unavailableReportId;
            f.submittedReportId = refs.submittedReportId;
            f.firmWorkerId = firmWorker.getId();
            f.inactiveWorkerId = inactiveWorker.getId();
            f.m2UnitId = m2.getId();
            f.workerRoleId = workerRole.getId();
        });
        return f;
    }

    /** A second project with its own worker + a single APPROVED report (for the scope test). */
    private Long seedOtherProject(Fixture f) {
        return tx().execute(status -> {
            MeasurementUnitEntity m2 = measurementUnitDao.findById(f.m2UnitId).orElseThrow();
            WorkerTypeEntity firm = workerTypeDao.findById(f.firmTypeId).orElseThrow();
            RoleEntity workerRole = roleDao.findById(f.workerRoleId).orElseThrow();

            WorkCategoryEntity cat = persistCategory("CATX_" + runId, 1);
            WorkItemEntity work = persistWorkItem("WI_OTHER_" + runId, cat, m2);

            ProjectEntity project = persistProject("Worker Cost IT OTHER");
            UserEntity worker = persistUser("other-" + runId, "Other", workerRole);
            persistMember(worker, project.getId(), workerRole, firm, AssignmentStatus.ACTIVE);
            f.otherReportId = persistReport(project, work, worker,
                    WorkReportStatus.APPROVED, "100.00", "1").getId();
            return project.getId();
        });
    }

    /** Scratch holder so the lambda can assign report ids before copying into the committed Fixture. */
    private static final class EntityRefs {
        Long firmReportId;
        Long uncategorizedReportId;
        Long inactiveReportId;
        Long noMemberReportId;
        Long unavailableReportId;
        Long submittedReportId;
    }

    // --- entity fixtures -----------------------------------------------------------------

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

    private WorkerTypeEntity persistWorkerType(String code, String tierPct, boolean base, int orderNo,
                                               boolean active, String nameRU, String namePL) {
        WorkerTypeEntity entity = new WorkerTypeEntity();
        entity.setCode(code);
        entity.setTierPct(new BigDecimal(tierPct));
        entity.setBase(base);
        entity.setOrderNo(orderNo);
        entity.setNameRU(nameRU);
        entity.setNamePL(namePL);
        entity.setActive(active);
        return workerTypeDao.save(entity);
    }

    private RoleEntity persistRole(String code) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        role.setNameRU("Роль " + runId);
        role.setNamePL("Rola " + runId);
        role.setSystem(false);
        return roleDao.save(role);
    }

    private WorkCategoryEntity persistCategory(String code, int orderNo) {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setCode(code);
        category.setOrderNo(orderNo);
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

    private ProjectEntity persistProject(String label) {
        ProjectEntity project = new ProjectEntity();
        project.setName(label + " " + runId + "-" + COUNTER.incrementAndGet());
        project.setStatus(ProjectStatus.ACTIVE);
        return projectDao.save(project);
    }

    private UserEntity persistUser(String key, String name, RoleEntity role) {
        UserEntity user = new UserEntity();
        user.setName(name);
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

    private WorkReportEntity persistReport(ProjectEntity project, WorkItemEntity workItem, UserEntity worker,
                                           WorkReportStatus statusValue, String clientUnitPrice,
                                           String totalQuantity) {
        WorkReportEntity report = new WorkReportEntity();
        report.setProject(project);
        report.setWorkItem(workItem);
        report.setAuthor(worker);
        report.setWorker(worker);
        report.setPerformedFrom(LocalDate.now().minusDays(2));
        report.setPerformedTo(LocalDate.now());
        report.setStatus(statusValue);
        report.setClientUnitPrice(new BigDecimal(clientUnitPrice));
        report.setTotalQuantity(new BigDecimal(totalQuantity));
        report.setRevenueValue(new BigDecimal(clientUnitPrice).multiply(new BigDecimal(totalQuantity)));
        return workReportDao.save(report);
    }
}
