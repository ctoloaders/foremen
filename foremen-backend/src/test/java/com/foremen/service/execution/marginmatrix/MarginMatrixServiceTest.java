package com.foremen.service.execution.marginmatrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;

import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.ProcurementLineDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.WorkReportDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportStatus;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.EstimateService;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.WorkReportServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-06 task 2.1 — unit tests for {@link MarginMatrixService#getMatrix(Long)}, the service wiring
 * that maps JPA entities into the pure {@link MarginMatrixAssembler}'s input records. The DAOs /
 * {@link EstimateService} are stubbed with Mockito and a <b>real</b> assembler is used, so the tests
 * assert the behavior unique to this task:
 * <ul>
 *   <li>the plan side maps from the estimate lines at the base tier (R1.1, R1.2, R2.3);</li>
 *   <li>each APPROVED report's worker tier resolves via {@code ProjectMember.workerType}, falling
 *       back to the base tier for an Uncategorized / inactive worker (R1.4, R2.5);</li>
 *   <li>a reconciled line is folded as linked material vs indirect-by-category (R3.1, R3.2);</li>
 *   <li>the read emits no notifications and performs no mutation (R8.1).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarginMatrixService.getMatrix wiring")
class MarginMatrixServiceTest {

    @Mock private WorkReportDao workReportDao;
    @Mock private WorkReportServiceMapper serviceMapper;
    @Mock private ProjectAccessCache projectAccessCache;
    @Mock private AuditLogDao auditLogDao;
    @Mock private EntityManager entityManager;
    @Mock private EstimateService estimateService;
    @Mock private EstimateLineDao estimateLineDao;
    @Mock private ProjectMemberDao projectMemberDao;
    @Mock private WorkerTypeDao workerTypeDao;
    @Mock private ProcurementLineDao procurementLineDao;

    private MarginMatrixService service;

    private static final Long PROJECT_ID = 42L;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("pl"));
        service = new MarginMatrixService(
                workReportDao,
                serviceMapper,
                projectAccessCache,
                auditLogDao,
                entityManager,
                estimateService,
                estimateLineDao,
                projectMemberDao,
                workerTypeDao,
                procurementLineDao,
                new MarginMatrixAssembler());
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    // --- Base / non-base tier fixtures ---------------------------------------------------------

    private WorkerTypeEntity baseType() {
        WorkerTypeEntity base = new WorkerTypeEntity();
        base.setId(1L);
        base.setCode("BASE");
        base.setNameRU("Базовый");
        base.setNamePL("Bazowy");
        base.setTierPct(new BigDecimal("0.40"));
        base.setBase(true);
        base.setOrderNo(0);
        base.setActive(true);
        return base;
    }

    private WorkerTypeEntity firmType() {
        WorkerTypeEntity firm = new WorkerTypeEntity();
        firm.setId(2L);
        firm.setCode("FIRM");
        firm.setNameRU("Фирма");
        firm.setNamePL("Firma");
        firm.setTierPct(new BigDecimal("0.65"));
        firm.setBase(false);
        firm.setOrderNo(3);
        firm.setActive(true);
        return firm;
    }

    private EstimateLineEntity planLine(long workItemId, String price, String qty, String valueNet) {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setId(10L);
        category.setCode("CAT");
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setOrderNo(1);

        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setId(5L);
        unit.setCode("m2");
        unit.setNameRU("м2");
        unit.setNamePL("m2");

        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setId(workItemId);
        workItem.setWorkCategory(category);
        workItem.setUnit(unit);
        workItem.setNameRU("Работа");
        workItem.setNamePL("Praca");
        workItem.setCode("1.01");

        EstimateLineEntity line = new EstimateLineEntity();
        line.setId(workItemId * 100);
        line.setWorkItem(workItem);
        line.setUnit(unit);
        line.setUnitPrice(price == null ? null : new BigDecimal(price));
        line.setQuantity(new BigDecimal(qty));
        line.setValueNet(new BigDecimal(valueNet));
        return line;
    }

    private WorkReportEntity approvedReport(long id, long workItemId, UserEntity worker,
                                            String clientUnitPrice, String totalQty, String revenue) {
        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setId(workItemId);

        WorkReportEntity report = new WorkReportEntity();
        report.setId(id);
        report.setWorkItem(workItem);
        report.setWorker(worker);
        report.setStatus(WorkReportStatus.APPROVED);
        report.setClientUnitPrice(new BigDecimal(clientUnitPrice));
        report.setTotalQuantity(new BigDecimal(totalQty));
        report.setRevenueValue(new BigDecimal(revenue));
        return report;
    }

    private UserEntity worker(long id, String name) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setName(name);
        return user;
    }

    private ProjectMemberEntity member(UserEntity user, WorkerTypeEntity type, AssignmentStatus status) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(PROJECT_ID);
        member.setWorkerType(type);
        member.setAssignmentStatus(status);
        return member;
    }

    private ProcurementLineEntity procLine(long id, Long matchedMaterialId, MatchStatus status,
                                            String lineTotal, UnplannedExpenseCategoryEntity category) {
        ProcurementDocumentEntity document = new ProcurementDocumentEntity();
        document.setId(900L);

        ProcurementLineEntity line = new ProcurementLineEntity();
        line.setId(id);
        line.setDocument(document);
        line.setLineNo((int) id);
        line.setRawName("line " + id);
        line.setMatchedMaterialId(matchedMaterialId);
        line.setMatchStatus(status);
        line.setMatchTier(matchedMaterialId == null ? MatchTier.NONE : MatchTier.EXACT);
        line.setLineTotal(new BigDecimal(lineTotal));
        line.setUnplannedCategory(category);
        return line;
    }

    private void stubEstimate() {
        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(7L);
        when(estimateService.getOrCreateEntityForProject(PROJECT_ID)).thenReturn(estimate);
    }

    // --- Tests ---------------------------------------------------------------------------------

    @Test
    @DisplayName("maps the plan side at the base tier and resolves a report's non-base worker tier")
    void mapsPlanAndFactWithResolvedTier() {
        stubEstimate();
        when(workerTypeDao.findActiveOrdered()).thenReturn(List.of(baseType(), firmType()));
        when(estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT_ID))
                .thenReturn(List.of(planLine(1L, "100.00", "2", "200.00")));

        UserEntity firmWorker = worker(500L, "Firma Sp.");
        when(workReportDao.findApprovedByProjectWithWorkItemAndWorker(PROJECT_ID))
                .thenReturn(List.of(approvedReport(1000L, 1L, firmWorker, "100.00", "1", "100.00")));
        when(projectMemberDao.findByProjectId(PROJECT_ID))
                .thenReturn(List.of(member(firmWorker, firmType(), AssignmentStatus.ACTIVE)));
        when(procurementLineDao.findByProjectIdWithUnplannedCategory(PROJECT_ID))
                .thenReturn(List.of());

        MarginMatrixDto dto = service.getMatrix(PROJECT_ID);

        assertThat(dto.projectId()).isEqualTo(PROJECT_ID);
        assertThat(dto.categories()).hasSize(1);
        MarginRowDto row = dto.categories().get(0).rows().get(0);
        assertThat(row.workItemId()).isEqualTo(1L);
        assertThat(row.workItemName()).isEqualTo("Praca");
        assertThat(row.category()).isEqualTo("Kategoria");
        assertThat(row.unit()).isEqualTo("m2");

        // Plan: Base_Cost = round(0.40×100 to 0.5) = 40.00; base uplift 0 ⇒ Plan_Cost = 40×2 = 80.00.
        assertThat(row.planRevenue()).isEqualByComparingTo("200.00");
        assertThat(row.planCost()).isEqualByComparingTo("80.00");

        // Fact (firm tier, +65%): Base_Cost = 40.00; Tier_Cost = 40×1.65 = 66.00; ×1 qty ⇒ 66.00.
        assertThat(row.factRevenue()).isEqualByComparingTo("100.00");
        assertThat(row.factCost()).isEqualByComparingTo("66.00");
        assertThat(row.reportCount()).isEqualTo(1);

        MarginReportDetailDto detail = row.reports().get(0);
        assertThat(detail.workerTypeId()).isEqualTo(2L);
        assertThat(detail.workerTypeName()).isEqualTo("Firma");
        assertThat(detail.baseTierFallback()).isFalse();
        assertThat(detail.derivedTierCost()).isEqualByComparingTo("66.00");

        // No notifications / no mutation side effects (R8.1): the mapper is pure plumbing, untouched.
        verifyNoInteractions(serviceMapper, auditLogDao);
    }

    @Test
    @DisplayName("falls back to the base tier for an Uncategorized worker (null worker type)")
    void fallsBackToBaseTierForUncategorizedWorker() {
        stubEstimate();
        when(workerTypeDao.findActiveOrdered()).thenReturn(List.of(baseType(), firmType()));
        when(estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT_ID))
                .thenReturn(List.of(planLine(1L, "100.00", "2", "200.00")));

        UserEntity uncategorized = worker(600L, "Jan");
        when(workReportDao.findApprovedByProjectWithWorkItemAndWorker(PROJECT_ID))
                .thenReturn(List.of(approvedReport(1001L, 1L, uncategorized, "100.00", "1", "100.00")));
        // Member exists and is ACTIVE but has a null worker type ⇒ Uncategorized_Worker.
        when(projectMemberDao.findByProjectId(PROJECT_ID))
                .thenReturn(List.of(member(uncategorized, null, AssignmentStatus.ACTIVE)));
        when(procurementLineDao.findByProjectIdWithUnplannedCategory(PROJECT_ID))
                .thenReturn(List.of());

        MarginMatrixDto dto = service.getMatrix(PROJECT_ID);

        MarginReportDetailDto detail = dto.categories().get(0).rows().get(0).reports().get(0);
        assertThat(detail.baseTierFallback()).isTrue();
        assertThat(detail.workerTypeId()).isNull();
        assertThat(detail.workerTypeName()).isEqualTo("Bazowy");
        // Base-tier fact cost: Base_Cost 40.00 × base uplift 0 × 1 qty = 40.00.
        assertThat(detail.derivedTierCost()).isEqualByComparingTo("40.00");
        assertThat(dto.categories().get(0).rows().get(0).factCost()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("falls back to the base tier when the worker has no project membership")
    void fallsBackToBaseTierForMissingMembership() {
        stubEstimate();
        when(workerTypeDao.findActiveOrdered()).thenReturn(List.of(baseType(), firmType()));
        when(estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT_ID))
                .thenReturn(List.of(planLine(1L, "100.00", "2", "200.00")));

        UserEntity stranger = worker(700L, "Anna");
        when(workReportDao.findApprovedByProjectWithWorkItemAndWorker(PROJECT_ID))
                .thenReturn(List.of(approvedReport(1002L, 1L, stranger, "100.00", "1", "100.00")));
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(procurementLineDao.findByProjectIdWithUnplannedCategory(PROJECT_ID)).thenReturn(List.of());

        MarginMatrixDto dto = service.getMatrix(PROJECT_ID);

        MarginReportDetailDto detail = dto.categories().get(0).rows().get(0).reports().get(0);
        assertThat(detail.baseTierFallback()).isTrue();
        assertThat(detail.workerTypeId()).isNull();
    }

    @Test
    @DisplayName("folds linked material vs indirect-by-category into the overall dashboard only")
    void foldsLinkedAndIndirectIntoDashboard() {
        stubEstimate();
        when(workerTypeDao.findActiveOrdered()).thenReturn(List.of(baseType()));
        when(estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT_ID))
                .thenReturn(List.of(planLine(1L, "100.00", "1", "100.00")));
        when(workReportDao.findApprovedByProjectWithWorkItemAndWorker(PROJECT_ID)).thenReturn(List.of());

        UnplannedExpenseCategoryEntity fuel = new UnplannedExpenseCategoryEntity();
        fuel.setId(20L);
        fuel.setCode("FUEL");
        fuel.setNameRU("Топливо");
        fuel.setNamePL("Paliwo");

        when(procurementLineDao.findByProjectIdWithUnplannedCategory(PROJECT_ID)).thenReturn(List.of(
                procLine(1L, 999L, MatchStatus.AUTO, "500.00", null),            // linked material
                procLine(2L, null, MatchStatus.MANUAL_UNMATCHED, "120.00", fuel), // indirect: fuel
                procLine(3L, null, MatchStatus.UNMATCHED, "30.00", null)));       // indirect: uncategorized

        MarginMatrixDto dto = service.getMatrix(PROJECT_ID);

        MarginDashboardDto dash = dto.dashboard();
        assertThat(dash.linkedMaterialCost()).isEqualByComparingTo("500.00");
        assertThat(dash.indirectCost()).isEqualByComparingTo("150.00");
        assertThat(dash.indirectByCategory()).hasSize(2);
        assertThat(dash.indirectByCategory())
                .anySatisfy(c -> {
                    assertThat(c.categoryId()).isEqualTo(20L);
                    assertThat(c.categoryName()).isEqualTo("Paliwo");
                    assertThat(c.amount()).isEqualByComparingTo("120.00");
                })
                .anySatisfy(c -> {
                    assertThat(c.categoryId()).isNull();
                    assertThat(c.categoryName()).isEqualTo("Bez kategorii");
                    assertThat(c.amount()).isEqualByComparingTo("30.00");
                });
        // Linked + indirect never touch a per-row row (R3.3): the single row has no material cost.
        assertThat(dto.categories().get(0).rows()).hasSize(1);
        // Overall cost = labour (0) + linked (500) + indirect (150).
        assertThat(dash.overallCost()).isEqualByComparingTo("650.00");
    }

    @Test
    @DisplayName("returns an empty matrix for a pre-estimate project without error")
    void emptyMatrixForPreEstimateProject() {
        stubEstimate();
        when(workerTypeDao.findActiveOrdered()).thenReturn(List.of(baseType()));
        when(estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT_ID)).thenReturn(List.of());
        when(workReportDao.findApprovedByProjectWithWorkItemAndWorker(PROJECT_ID)).thenReturn(List.of());
        when(procurementLineDao.findByProjectIdWithUnplannedCategory(PROJECT_ID)).thenReturn(List.of());

        MarginMatrixDto dto = service.getMatrix(PROJECT_ID);

        assertThat(dto.projectId()).isEqualTo(PROJECT_ID);
        assertThat(dto.categories()).isEmpty();
        assertThat(dto.dashboard().overallCost()).isEqualByComparingTo("0");
        assertThat(dto.dashboard().linkedMaterialCost()).isEqualByComparingTo("0");
    }
}
