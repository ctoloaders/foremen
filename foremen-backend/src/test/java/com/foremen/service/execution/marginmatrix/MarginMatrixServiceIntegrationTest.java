package com.foremen.service.execution.marginmatrix;

import java.math.BigDecimal;
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

import com.foremen.dao.CurrencyDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UnplannedExpenseCategoryDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkReportDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportStatus;
import com.foremen.dao.model.WorkerTypeEntity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-06-06 task 6.2 — Spring integration test for {@link MarginMatrixService#getMatrix(Long)}, the
 * margin-matrix read model, over a <strong>directly seeded</strong> project graph against a real
 * PostgreSQL (Testcontainers). It drives the real Spring-wired service end-to-end through the shipped
 * estimate get-or-create path, the APPROVED-work-report read, the worker-tier resolution via
 * {@code ProjectMember.workerType}, the active {@code WorkerType} dictionary, the reconciled
 * procurement-line read, and the pure {@link MarginMatrixAssembler}.
 *
 * <p>Harness mirrors {@code MarginsListServiceIntegrationTest}:
 * {@code @SpringBootTest(MOCK)} + {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")}
 * where Liquibase is disabled and Hibernate {@code create-drop} builds the schema — so the WorkerType
 * / role seeds do <em>not</em> pre-populate; this test seeds its own base + firm tiers and its own
 * roles/users. Reports, estimate lines, and reconciled procurement lines are seeded directly (no live
 * work-reporting / OCR dependency), per the task.
 *
 * <p><b>What is asserted (R1.1, R2.5, R3.1, R3.2, R6.2, R6.3, R9.1):</b>
 * <ul>
 *   <li><b>Joined rows + per-report tier cost mix.</b> Over a priced work item with two APPROVED
 *       reports staffed by different worker types, the fact cost sums each report at its own tier (firm
 *       {@code +65%} vs the base-tier Uncategorized fallback), and the row fact cost equals the sum of
 *       the per-report detail costs (R1.3, R2.4, R4.4).</li>
 *   <li><b>Base-tier fallback for an Uncategorized worker.</b> The report whose member carries a null
 *       worker type resolves to the base tier with {@code baseTierFallback = true} (R1.4, R2.5).</li>
 *   <li><b>Linked-material fold + separate indirect-by-category sum, overall only.</b> The linked
 *       purchase sums into {@code linkedMaterialCost}; the unlinked lines sum into {@code indirectCost}
 *       broken down by category (+ the uncategorized bucket); neither touches a per-row row (R3.1,
 *       R3.2, R3.3), and both enter only {@code overallCost} (R3.4).</li>
 *   <li><b>Unavailable-price {@code —}.</b> A work item with a null {@code unitPrice} has a null
 *       {@code planCost}/{@code planMargin} and is excluded from the plan totals (R1.6).</li>
 *   <li><b>Under-margin flag + dashboard totals.</b> The priced row is flagged under-margin (fact
 *       margin % materially below plan), and the dashboard folds the labour / linked / indirect totals
 *       (R4.3, R3.4).</li>
 *   <li><b>Project scope (R6.3).</b> Data seeded under a second project never appears in the first
 *       project's matrix.</li>
 * </ul>
 *
 * <p><b>Repeatability.</b> Every fixture row carries a unique per-run id (UUID substring) and
 * {@link #cleanUp()} removes every row this test created (deepest-FK-first) after each test, so the
 * suite re-runs without manual DB cleanup.
 *
 * <p>Validates: Requirements 1.1, 2.5, 3.1, 3.2, 6.2, 6.3, 9.1
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
@org.junit.jupiter.api.Tag("Feature: FOR-06-06-margin-matrix, task 6.2: getMatrix integration + project scope")
class MarginMatrixServiceIntegrationTest {

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
    private MarginMatrixService marginMatrixService;

    @Autowired
    private ProjectDao projectDao;
    @Autowired
    private CurrencyDao currencyDao;
    @Autowired
    private MeasurementUnitDao measurementUnitDao;
    @Autowired
    private WorkCategoryDao workCategoryDao;
    @Autowired
    private WorkItemDao workItemDao;
    @Autowired
    private WorkerTypeDao workerTypeDao;
    @Autowired
    private EstimateDao estimateDao;
    @Autowired
    private EstimateLineDao estimateLineDao;
    @Autowired
    private RoleDao roleDao;
    @Autowired
    private UserDao userDao;
    @Autowired
    private ProjectMemberDao projectMemberDao;
    @Autowired
    private WorkReportDao workReportDao;
    @Autowired
    private ProcurementDocumentDao procurementDocumentDao;
    @Autowired
    private UnplannedExpenseCategoryDao unplannedExpenseCategoryDao;

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
            exec("delete from ProcurementLineEntity l where l.document.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from ProcurementDocumentEntity d where d.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from WorkReportEntity r where r.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from EstimateLineEntity el where el.estimate.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from EstimateEntity e where e.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from ProjectMemberEntity m where m.projectId in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from ProjectEntity pr where pr.name like :p");
            exec("delete from UserEntity u where u.email like :p");
            exec("delete from WorkItemEntity wi where wi.id in "
                    + "(select w.id from WorkItemEntity w where w.workCategory.code like :p)");
            exec("delete from WorkCategoryEntity wc where wc.code like :p");
            exec("delete from WorkerTypeEntity w where w.code like :p");
            exec("delete from UnplannedExpenseCategoryEntity c where c.code like :p");
            exec("delete from RoleEntity r where r.code like :p");
            // The shared "PLN" currency + "m2" unit fixtures are intentionally NOT deleted (reused).
        });
    }

    private void exec(String jpql) {
        entityManager.createQuery(jpql).setParameter("p", "%" + runId + "%").executeUpdate();
    }

    // =====================================================================================
    // Tests
    // =====================================================================================

    @Test
    @DisplayName("getMatrix joins plan × fact rows: per-report tier mix, base-tier fallback, "
            + "linked/indirect folded only in the overall dashboard, unavailable-price row, under-margin "
            + "flag, dashboard totals (R1.1, R2.5, R3.1, R3.2)")
    void getMatrixOverSeededProject() {
        Fixture f = seedFixture();

        MarginMatrixDto dto = marginMatrixService.getMatrix(f.projectId);

        assertThat(dto).as("the read model must be non-null (R1.1)").isNotNull();
        assertThat(dto.projectId()).isEqualTo(f.projectId);

        // --- rows: one per estimate work item, grouped by work category in estimate order ---------
        assertThat(dto.categories())
                .as("two categories, in estimate order (priced CAT1 then unavailable CAT2)")
                .hasSize(2);
        MarginRowDto priced = dto.categories().get(0).rows().get(0);
        MarginRowDto unavailable = dto.categories().get(1).rows().get(0);

        assertThat(priced.workItemId()).isEqualTo(f.pricedWorkItemId);
        assertThat(priced.unit()).isEqualTo("m2");

        // --- plan side (base tier): Base_Cost = round(0.40×100 to 0.5) = 40; ×2 qty ⇒ 80 ----------
        assertThat(priced.planRevenue()).isEqualByComparingTo("200.00");
        assertThat(priced.planCost()).isEqualByComparingTo("80.00");
        assertThat(priced.planMarginAmount()).isEqualByComparingTo("120.00");

        // --- fact side: two reports, each costed at its OWN tier (per-report tier mix) -------------
        // firm (+65%): Base_Cost 40 × 1.65 = 66.00; Uncategorized → base tier: 40.00.
        assertThat(priced.reportCount()).isEqualTo(2);
        assertThat(priced.factRevenue()).isEqualByComparingTo("200.00");
        assertThat(priced.factCost()).isEqualByComparingTo("106.00");
        assertThat(priced.factMarginAmount()).isEqualByComparingTo("94.00");

        // The row fact cost equals the sum of the per-report detail derived costs (R4.4).
        BigDecimal detailSum = priced.reports().stream()
                .map(MarginReportDetailDto::derivedTierCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(detailSum)
                .as("the row fact cost equals the sum of the detail derived costs (R4.4)")
                .isEqualByComparingTo(priced.factCost());

        // --- base-tier fallback for the Uncategorized worker (R2.5) --------------------------------
        MarginReportDetailDto firmDetail = priced.reports().stream()
                .filter(r -> !r.baseTierFallback()).findFirst().orElseThrow();
        MarginReportDetailDto fallbackDetail = priced.reports().stream()
                .filter(MarginReportDetailDto::baseTierFallback).findFirst().orElseThrow();
        assertThat(firmDetail.derivedTierCost()).isEqualByComparingTo("66.00");
        assertThat(firmDetail.workerTypeId()).isEqualTo(f.firmTypeId);
        assertThat(fallbackDetail.workerTypeId()).as("fallback carries a null worker type id").isNull();
        assertThat(fallbackDetail.derivedTierCost())
                .as("the Uncategorized worker falls back to the base tier (40.00)")
                .isEqualByComparingTo("40.00");

        // --- under-margin flag: plan pct 0.60 vs fact pct 0.47 ⇒ diff 0.13 ≥ 0.05 ------------------
        assertThat(priced.underMargin())
                .as("the priced row is flagged under-margin (fact % materially below plan %, R4.3)")
                .isTrue();

        // --- unavailable-price row (zero unitPrice ⇒ null plan cost/margin, R1.6) -------------------
        assertThat(unavailable.workItemId()).isEqualTo(f.unavailableWorkItemId);
        assertThat(unavailable.planCost()).as("a null-price row has an unavailable plan cost (R1.6)").isNull();
        assertThat(unavailable.planMarginAmount()).isNull();
        assertThat(unavailable.planMarginPct()).isNull();
        assertThat(unavailable.reportCount()).as("the unavailable row has no approved reports").isZero();
        assertThat(unavailable.factMarginPct()).as("a zero-report row has an unavailable fact % (R1.5)").isNull();

        // --- dashboard: linked material + indirect-by-category folded ONLY into the overall ---------
        MarginDashboardDto dash = dto.dashboard();
        assertThat(dash.linkedMaterialCost()).isEqualByComparingTo("500.00");
        assertThat(dash.indirectCost()).isEqualByComparingTo("150.00");
        assertThat(dash.indirectByCategory())
                .as("indirect broken down by category + an uncategorized bucket (R3.2)")
                .hasSize(2)
                .anySatisfy(c -> {
                    assertThat(c.categoryId()).isEqualTo(f.fuelCategoryId);
                    assertThat(c.amount()).isEqualByComparingTo("120.00");
                })
                .anySatisfy(c -> {
                    assertThat(c.categoryId()).as("the uncategorized bucket carries a null id").isNull();
                    assertThat(c.amount()).isEqualByComparingTo("30.00");
                });

        // Neither the linked material nor the indirect cost touches a per-row row (R3.3): the priced
        // row's fact cost is labour-only (106.00, asserted above).
        // Overall: labour revenue 200; overall cost = labour 106 + linked 500 + indirect 150 = 756.
        assertThat(dash.factLabourRevenue()).isEqualByComparingTo("200.00");
        assertThat(dash.factLabourCost()).isEqualByComparingTo("106.00");
        assertThat(dash.overallRevenue()).isEqualByComparingTo("200.00");
        assertThat(dash.overallCost()).isEqualByComparingTo("756.00");

        // Plan totals exclude the unavailable-price row from the denominators (R1.6, R3.5).
        assertThat(dash.planRevenue())
                .as("the plan revenue total excludes the unavailable-price row (R1.6)")
                .isEqualByComparingTo("200.00");
        assertThat(dash.planCost()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("getMatrix never returns another project's data — project scope (R6.3)")
    void getMatrixIsProjectScoped() {
        Fixture f = seedFixture();
        // A second project with its own estimate line, approved report, and linked purchase.
        Long otherProjectId = seedOtherProject(f);

        MarginMatrixDto dto = marginMatrixService.getMatrix(f.projectId);

        // The first project's matrix must carry ONLY its own rows (CAT1 priced + CAT2 unavailable),
        // never the other project's work item, and its dashboard must not include the other project's
        // linked purchase.
        assertThat(dto.categories()).hasSize(2);
        assertThat(dto.categories().stream().flatMap(g -> g.rows().stream())
                .map(MarginRowDto::workItemId))
                .as("only the first project's work items appear (R6.3)")
                .containsExactlyInAnyOrder(f.pricedWorkItemId, f.unavailableWorkItemId)
                .doesNotContain(f.otherWorkItemId);
        assertThat(dto.dashboard().linkedMaterialCost())
                .as("the other project's linked purchase (777.00) must not leak in (R6.3)")
                .isEqualByComparingTo("500.00");

        // And the other project's matrix carries only ITS own single row.
        MarginMatrixDto otherDto = marginMatrixService.getMatrix(otherProjectId);
        assertThat(otherDto.categories().stream().flatMap(g -> g.rows().stream())
                .map(MarginRowDto::workItemId))
                .as("the other project's matrix carries only its own work item (R6.3)")
                .containsExactly(f.otherWorkItemId);
        assertThat(otherDto.dashboard().linkedMaterialCost()).isEqualByComparingTo("777.00");
    }

    // =====================================================================================
    // Fixture building
    // =====================================================================================

    /** The ids a test needs, captured after the fixture graph is committed. */
    private static final class Fixture {
        Long projectId;
        Long pricedWorkItemId;
        Long unavailableWorkItemId;
        Long firmTypeId;
        Long fuelCategoryId;
        // shared reference data reused by the second project
        Long baseTypeId;
        Long m2UnitId;
        Long currencyId;
        Long workerRoleId;
        Long otherWorkItemId;
    }

    /**
     * Seeds a self-contained project graph in one transaction: a base + firm WorkerType tier, a PLN
     * currency + m2 unit, two work categories each with one work item, an estimate with a priced line
     * (CAT1, 100.00 × 2) and an unavailable-price line (CAT2, a 0.00 price), two WORKER members (a firm
     * worker and an Uncategorized worker with a null worker type), two APPROVED reports on the priced
     * work item (one per worker), and a procurement document with a linked line + two indirect lines
     * (fuel + uncategorized).
     */
    private Fixture seedFixture() {
        Fixture f = new Fixture();
        tx().executeWithoutResult(status -> {
            CurrencyEntity currency = persistCurrency();
            MeasurementUnitEntity m2 = persistUnit("m2");
            WorkerTypeEntity base = persistWorkerType("WT_BASE_" + runId, "0.40", true, 0, "Базовый", "Bazowy");
            WorkerTypeEntity firm = persistWorkerType("WT_FIRM_" + runId, "0.65", false, 3, "Фирма", "Firma");
            RoleEntity workerRole = persistRole("WORKER_" + runId);

            WorkCategoryEntity cat1 = persistCategory("CAT1_" + runId, 1);
            WorkCategoryEntity cat2 = persistCategory("CAT2_" + runId, 2);
            WorkItemEntity pricedWork = persistWorkItem("WI_PRICED_" + runId, cat1, m2);
            WorkItemEntity unavailableWork = persistWorkItem("WI_UNAVAIL_" + runId, cat2, m2);

            ProjectEntity project = persistProject("Margin Matrix IT");
            EstimateEntity estimate = persistEstimate(project, currency);
            // Priced line: 100.00 × 2 = 200.00; unavailable line: a zero price (the estimate_lines
            // unit_price column is NOT NULL, so an "unavailable" row carries 0.00 — which
            // MarginCostService.baseCost treats as unavailable, exactly like null, R1.6).
            persistLine(estimate, pricedWork, m2, "100.00", "2", "200.00");
            persistLine(estimate, unavailableWork, m2, "0.00", "5", "0.00");

            UserEntity firmWorker = persistUser("firm-" + runId, "Firma Sp.", workerRole);
            UserEntity uncategorizedWorker = persistUser("unc-" + runId, "Jan", workerRole);
            persistMember(firmWorker, project.getId(), workerRole, firm);           // firm tier
            persistMember(uncategorizedWorker, project.getId(), workerRole, null);   // Uncategorized

            // Two APPROVED reports on the priced work item: firm + Uncategorized, each 100.00 × 1.
            persistApprovedReport(project, pricedWork, firmWorker, "100.00", "1", "100.00");
            persistApprovedReport(project, pricedWork, uncategorizedWorker, "100.00", "1", "100.00");

            UnplannedExpenseCategoryEntity fuel = persistUnplannedCategory("FUEL_" + runId, "Топливо", "Paliwo");
            ProcurementDocumentEntity doc = persistProcurementDocument(project, firmWorker);
            // Linked material (overall only), indirect fuel, indirect uncategorized.
            persistLine(doc, 1, 999L, MatchStatus.AUTO, "500.00", null);
            persistLine(doc, 2, null, MatchStatus.MANUAL_UNMATCHED, "120.00", fuel);
            persistLine(doc, 3, null, MatchStatus.UNMATCHED, "30.00", null);
            procurementDocumentDao.save(doc);

            f.projectId = project.getId();
            f.pricedWorkItemId = pricedWork.getId();
            f.unavailableWorkItemId = unavailableWork.getId();
            f.firmTypeId = firm.getId();
            f.fuelCategoryId = fuel.getId();
            f.baseTypeId = base.getId();
            f.m2UnitId = m2.getId();
            f.currencyId = currency.getId();
            f.workerRoleId = workerRole.getId();
        });
        return f;
    }

    /** A second project with its own estimate line, APPROVED report, and a 777.00 linked purchase. */
    private Long seedOtherProject(Fixture f) {
        return tx().execute(status -> {
            CurrencyEntity currency = currencyDao.findById(f.currencyId).orElseThrow();
            MeasurementUnitEntity m2 = measurementUnitDao.findById(f.m2UnitId).orElseThrow();
            WorkerTypeEntity firm = workerTypeDao.findById(f.firmTypeId).orElseThrow();
            RoleEntity workerRole = roleDao.findById(f.workerRoleId).orElseThrow();

            WorkCategoryEntity cat = persistCategory("CATX_" + runId, 1);
            WorkItemEntity work = persistWorkItem("WI_OTHER_" + runId, cat, m2);
            f.otherWorkItemId = work.getId();

            ProjectEntity project = persistProject("Margin Matrix IT OTHER");
            EstimateEntity estimate = persistEstimate(project, currency);
            persistLine(estimate, work, m2, "100.00", "1", "100.00");

            UserEntity worker = persistUser("other-" + runId, "Other", workerRole);
            persistMember(worker, project.getId(), workerRole, firm);
            persistApprovedReport(project, work, worker, "100.00", "1", "100.00");

            ProcurementDocumentEntity doc = persistProcurementDocument(project, worker);
            persistLine(doc, 1, 888L, MatchStatus.AUTO, "777.00", null);
            procurementDocumentDao.save(doc);

            return project.getId();
        });
    }

    // --- entity fixtures -----------------------------------------------------------------

    private CurrencyEntity persistCurrency() {
        return currencyDao.findByCode("PLN").orElseGet(() -> {
            CurrencyEntity currency = new CurrencyEntity();
            currency.setCode("PLN");
            currency.setSymbol("zł");
            currency.setNameRU("Злотый");
            currency.setNamePL("Złoty");
            currency.setActive(true);
            return currencyDao.save(currency);
        });
    }

    /** A shared measurement unit reused across runs (found-or-created by its stable {@code code}). */
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

    private WorkerTypeEntity persistWorkerType(
            String code, String tierPct, boolean base, int orderNo, String nameRU, String namePL) {
        WorkerTypeEntity entity = new WorkerTypeEntity();
        entity.setCode(code);
        entity.setTierPct(new BigDecimal(tierPct));
        entity.setBase(base);
        entity.setOrderNo(orderNo);
        entity.setNameRU(nameRU);
        entity.setNamePL(namePL);
        entity.setActive(true);
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

    private EstimateEntity persistEstimate(ProjectEntity project, CurrencyEntity currency) {
        EstimateEntity estimate = new EstimateEntity();
        estimate.setProject(project);
        estimate.setCurrency(currency);
        estimate.setStatus(EstimateStatus.DRAFT);
        return estimateDao.save(estimate);
    }

    private void persistLine(EstimateEntity estimate, WorkItemEntity workItem, MeasurementUnitEntity unit,
                             String unitPrice, String quantity, String valueNet) {
        EstimateLineEntity line = new EstimateLineEntity();
        line.setEstimate(estimate);
        line.setWorkItem(workItem);
        line.setUnit(unit);
        line.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        line.setQuantity(new BigDecimal(quantity));
        line.setValueNet(new BigDecimal(valueNet));
        estimateLineDao.save(line);
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

    private void persistMember(UserEntity user, Long projectId, RoleEntity role, WorkerTypeEntity workerType) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(projectId);
        member.setProjectRole(role);
        member.setWorkerType(workerType);
        member.setAssignmentStatus(AssignmentStatus.ACTIVE);
        projectMemberDao.save(member);
    }

    private void persistApprovedReport(ProjectEntity project, WorkItemEntity workItem, UserEntity worker,
                                       String clientUnitPrice, String totalQuantity, String revenue) {
        WorkReportEntity report = new WorkReportEntity();
        report.setProject(project);
        report.setWorkItem(workItem);
        report.setAuthor(worker);
        report.setWorker(worker);
        report.setPerformedFrom(LocalDate.now().minusDays(2));
        report.setPerformedTo(LocalDate.now());
        report.setStatus(WorkReportStatus.APPROVED);
        report.setClientUnitPrice(new BigDecimal(clientUnitPrice));
        report.setTotalQuantity(new BigDecimal(totalQuantity));
        report.setRevenueValue(new BigDecimal(revenue));
        workReportDao.save(report);
    }

    private UnplannedExpenseCategoryEntity persistUnplannedCategory(String code, String nameRU, String namePL) {
        UnplannedExpenseCategoryEntity category = new UnplannedExpenseCategoryEntity();
        category.setCode(code);
        category.setNameRU(nameRU);
        category.setNamePL(namePL);
        category.setActive(true);
        return unplannedExpenseCategoryDao.save(category);
    }

    private ProcurementDocumentEntity persistProcurementDocument(ProjectEntity project, UserEntity uploadedBy) {
        ProcurementDocumentEntity doc = new ProcurementDocumentEntity();
        doc.setProject(project);
        doc.setUploadedBy(uploadedBy);
        doc.setRawObjectKey("raw-" + runId);
        doc.setSourceMimeType("image/png");
        doc.setStatus(ProcessingStatus.PARSED);
        doc.setUploadedAt(java.time.OffsetDateTime.now());
        return doc;
    }

    private void persistLine(ProcurementDocumentEntity doc, int lineNo, Long matchedMaterialId,
                             MatchStatus status, String lineTotal, UnplannedExpenseCategoryEntity category) {
        ProcurementLineEntity line = new ProcurementLineEntity();
        line.setDocument(doc);
        line.setLineNo(lineNo);
        line.setRawName("line " + lineNo + " " + runId);
        line.setMatchedMaterialId(matchedMaterialId);
        line.setMatchStatus(status);
        line.setMatchTier(matchedMaterialId == null ? MatchTier.NONE : MatchTier.EXACT);
        line.setLineTotal(new BigDecimal(lineTotal));
        line.setUnplannedCategory(category);
        doc.getLines().add(line);
    }
}
