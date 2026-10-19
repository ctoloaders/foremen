package com.foremen.service.procurement.matrix;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.foremen.dao.ProcurementLineDao;
import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.PurchaseLineReconciliationService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.materials.MaterialBranchGroupDto;
import com.foremen.service.estimate.materials.MaterialRowDto;
import com.foremen.service.estimate.materials.MaterialsListDto;
import com.foremen.service.estimate.materials.MaterialsListService;
import com.foremen.service.estimate.materials.MoneyBrutto;
import com.foremen.service.model.PurchaseLineServiceModel;
import com.foremen.service.model.mapper.ProcurementLineServiceMapper;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * FOR-06-05 (design §Testing — backend example / integration tests; task 6.2) — example tests for
 * {@link MaterialsMatrixService#getMatrix(Long)}, the matrix read model over a seeded project.
 *
 * <p>The service is a pure read-only projection over two reused read models, so the two seams are
 * stubbed — the FOR-05-05b {@link MaterialsListService} (plan side) and the FOR-06-04
 * {@link PurchaseLineReconciliationService#list} (fact side) — around the <em>real</em>
 * {@link MaterialsMatrixAssembler}. The CRUD-plumbing collaborators ({@link ProcurementLineDao},
 * {@link ProcurementLineServiceMapper}, {@link ProjectAccessCache}, {@link AuditLogDao},
 * {@link EntityManager}) are only needed to satisfy the {@code ProjectScopedService} contract and are
 * never touched by {@code getMatrix}. This asserts the end-to-end join (plan × fact) exactly as a
 * seeded project would produce it, without a Spring context or database.
 *
 * <p>Covered (Requirements 1.1, 6.3):
 * <ul>
 *   <li>the joined rows — each planned material keeps its plan data and gets its fact aggregation;</li>
 *   <li><b>analog aggregation</b> — three distinct products (different manufacturers, different tiers)
 *       linked to one planned material sum into one row &gt; 100 %, uncapped;</li>
 *   <li>the <b>unit-mismatch</b> exclusion + count — a foreign-unit line is dropped from the quantity
 *       sum but kept in the money;</li>
 *   <li>the <b>null-brutto</b> row — {@code —} (null pct), excluded from money totals;</li>
 *   <li>the <b>dashboard totals</b> — per-branch and project-wide, over non-null-brutto rows;</li>
 *   <li>the <b>project-scope</b> contract — {@link MaterialsMatrixService#getProjectIdPath()} resolves
 *       the owning project so the read is auto project-filtered (R6.3), and the service passes the
 *       {@code projectId} straight through to the already project-scoped FOR-06-04 read.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialsMatrixServiceTest {

    private static final long PROJECT_ID = 7L;
    private static final long OTHER_PROJECT_ID = 99L;

    // --- planned material ids ---
    private static final long CEMENT_ID = 101L;   // construction, over-purchased via 3 analogs
    private static final long BRICK_ID = 102L;    // construction, unit-mismatch on one line
    private static final long PAINT_ID = 201L;    // finishing, null planned brutto (—)

    @Mock private ProcurementLineDao lineDao;
    @Mock private ProcurementLineServiceMapper serviceMapper;
    @Mock private ProjectAccessCache projectAccessCache;
    @Mock private AuditLogDao auditLogDao;
    @Mock private EntityManager entityManager;
    @Mock private MaterialsListService materialsListService;
    @Mock private PurchaseLineReconciliationService reconciliationService;

    private MaterialsMatrixService service;

    @BeforeEach
    void setUp() {
        service = new MaterialsMatrixService(
                lineDao, serviceMapper, projectAccessCache, auditLogDao, entityManager,
                materialsListService, reconciliationService,
                new MaterialsMatrixAssembler());
    }

    // --- Project scope (R6.3) ------------------------------------------------------------------

    @Test
    void getProjectIdPathResolvesOwningProjectForAutoFiltering() {
        // The single mandatory ProjectScopedService override: a procurement line resolves its project
        // via document.project.id, so any LIST read is auto project-filtered (R6.3).
        assertThat(service.getProjectIdPath()).isEqualTo("document.project.id");
    }

    @Test
    void getMatrixReadsOnlyTheRequestedProjectsLines() {
        // The fact side is the FOR-06-04 list read, which is itself project-scoped. The matrix must
        // pass the requested projectId straight through — never OTHER_PROJECT_ID — so it can never
        // surface another project's lines (R6.3). Stub the requested project's read; the other
        // project's read is deliberately left returning an empty list.
        when(materialsListService.getMaterials(PROJECT_ID, false))
                .thenReturn(materialsList(List.of(
                        plan(CEMENT_ID, "Цемент", ConsumptionBranch.construction, "Цемент", "kg",
                                bd("10"), bd("1000")))));
        when(reconciliationService.list(PROJECT_ID, null, null, null))
                .thenReturn(List.of(matched(1L, CEMENT_ID, MatchTier.EXACT, bd("5"), "kg", bd("500"))));
        // A line that belongs to another project would only appear if the matrix queried that project.
        when(reconciliationService.list(OTHER_PROJECT_ID, null, null, null))
                .thenReturn(List.of(matched(9L, CEMENT_ID, MatchTier.EXACT, bd("99"), "kg", bd("9999"))));

        MaterialsMatrixDto dto = service.getMatrix(PROJECT_ID);

        MatrixRowDto cement = rowOf(dto, ConsumptionBranch.construction, CEMENT_ID);
        // Only the requested project's single 500 line is aggregated — not the other project's 9999.
        assertThat(cement.factBrutto()).isEqualByComparingTo(bd("500"));
        assertThat(cement.linkedLineCount()).isEqualTo(1);
    }

    // --- The joined matrix: analogs, unit-mismatch, null-brutto, dashboard (R1.1) --------------

    @Test
    void getMatrixJoinsPlanAndFactWithAnalogAggregationUnitMismatchAndDashboards() {
        when(materialsListService.getMaterials(PROJECT_ID, false))
                .thenReturn(materialsList(List.of(
                        // construction
                        plan(CEMENT_ID, "Цемент", ConsumptionBranch.construction, "Цемент", "kg",
                                bd("10"), bd("1000")),
                        plan(BRICK_ID, "Кирпич", ConsumptionBranch.construction, "Кирпич", "szt",
                                bd("200"), bd("500")),
                        // finishing — null planned brutto (no chosen-product price)
                        planNullPrice(PAINT_ID, "Краска", ConsumptionBranch.finishing, "Краска", "l",
                                bd("20")))));

        when(reconciliationService.list(PROJECT_ID, null, null, null)).thenReturn(List.of(
                // Cement — THREE distinct analog products, different manufacturers, different tiers,
                // all same unit: 600 + 500 + 100 = 1200 ⇒ 120 % (over-purchased, uncapped).
                matchedProducer(1L, CEMENT_ID, MatchTier.EXACT, bd("6"), "kg", bd("600"), "Lafarge"),
                matchedProducer(2L, CEMENT_ID, MatchTier.MANUFACTURER_CATEGORY, bd("5"), "kg", bd("500"), "Cemex"),
                matchedProducer(3L, CEMENT_ID, MatchTier.CATEGORY, bd("1"), "kg", bd("100"), "Górażdże"),
                // Brick — two lines, one in a foreign unit ("kg" ≠ norm "szt"): the foreign line is
                // excluded from the quantity sum but kept in the money.
                matched(4L, BRICK_ID, MatchTier.EXACT, bd("150"), "szt", bd("300")),
                matched(5L, BRICK_ID, MatchTier.CATEGORY, bd("40"), "kg", bd("100")),
                // Paint — one matched line against the null-brutto row (shown, excluded from totals).
                matched(6L, PAINT_ID, MatchTier.EXACT, bd("18"), "l", bd("360")),
                // A deliberately non-contributing line: unmatched ⇒ belongs to no row (R1.6).
                unmatched(7L)));

        MaterialsMatrixDto dto = service.getMatrix(PROJECT_ID);

        assertThat(dto.projectId()).isEqualTo(PROJECT_ID);

        // --- Cement: analog aggregation across tiers, uncapped over-purchase ---
        MatrixRowDto cement = rowOf(dto, ConsumptionBranch.construction, CEMENT_ID);
        assertThat(cement.linkedLineCount()).isEqualTo(3);
        assertThat(cement.factBrutto()).isEqualByComparingTo(bd("1200"));
        assertThat(cement.fulfilmentPercent()).isEqualByComparingTo(bd("120.0000"));
        assertThat(cement.fillIndicator()).isEqualByComparingTo(bd("100")); // bar capped at 100
        assertThat(cement.overPurchased()).isTrue();
        assertThat(cement.unitMismatchCount()).isZero();
        assertThat(cement.factQuantity()).isEqualByComparingTo(bd("12")); // 6 + 5 + 1, all "kg"
        // Three distinct manufacturers listed together under the one planned material (R4.3).
        assertThat(cement.linkedLines()).extracting(MatrixLinkedLineDto::manufacturerHint)
                .containsExactlyInAnyOrder("Lafarge", "Cemex", "Górażdże");

        // --- Brick: unit-mismatch exclusion + count ---
        MatrixRowDto brick = rowOf(dto, ConsumptionBranch.construction, BRICK_ID);
        assertThat(brick.linkedLineCount()).isEqualTo(2);
        assertThat(brick.factBrutto()).isEqualByComparingTo(bd("400"));   // 300 + 100 (money unaffected)
        assertThat(brick.factQuantity()).isEqualByComparingTo(bd("150")); // only the "szt" line
        assertThat(brick.unitMismatchCount()).isEqualTo(1);
        assertThat(brick.unitMismatch()).isTrue();
        // 400 / 500 * 100 = 80 %, not over-purchased.
        assertThat(brick.fulfilmentPercent()).isEqualByComparingTo(bd("80.0000"));
        assertThat(brick.overPurchased()).isFalse();
        // The foreign-unit line is marked in the detail.
        assertThat(brick.linkedLines()).filteredOn(MatrixLinkedLineDto::unitMismatch).hasSize(1);

        // --- Paint: null planned brutto ⇒ pct null (—), excluded from totals ---
        MatrixRowDto paint = rowOf(dto, ConsumptionBranch.finishing, PAINT_ID);
        assertThat(paint.plannedBrutto()).isNull();
        assertThat(paint.fulfilmentPercent()).isNull(); // rendered "—"
        assertThat(paint.fillIndicator()).isNull();
        assertThat(paint.overPurchased()).isFalse();
        assertThat(paint.factBrutto()).isEqualByComparingTo(bd("360")); // still aggregated/visible
        assertThat(paint.linkedLineCount()).isEqualTo(1);

        // --- Dashboard totals (R3.2, R3.3) ---
        // Construction branch: planned 1000 + 500 = 1500; fact 1200 + 400 = 1600 ⇒ 106.6667 %.
        MatrixBranchGroupDto construction = branchOf(dto, ConsumptionBranch.construction);
        assertThat(construction.plannedBrutto()).isEqualByComparingTo(bd("1500"));
        assertThat(construction.factBrutto()).isEqualByComparingTo(bd("1600"));
        assertThat(construction.aggregatePercent()).isEqualByComparingTo(bd("106.6667"));

        // Finishing branch: the only row has a null planned brutto ⇒ excluded from money totals, so
        // the branch planned/fact totals stay null and the aggregate pct is null (R3.3).
        MatrixBranchGroupDto finishing = branchOf(dto, ConsumptionBranch.finishing);
        assertThat(finishing.plannedBrutto()).isNull();
        assertThat(finishing.factBrutto()).isNull();
        assertThat(finishing.aggregatePercent()).isNull();

        // Grand total = sum of the per-branch totals (only construction contributes).
        assertThat(dto.grandPlannedBrutto()).isEqualByComparingTo(bd("1500"));
        assertThat(dto.grandFactBrutto()).isEqualByComparingTo(bd("1600"));
        assertThat(dto.grandAggregatePercent()).isEqualByComparingTo(bd("106.6667"));
    }

    @Test
    void getMatrixOnPreEstimateProjectReturnsEmptyStructureNotError() {
        // A pre-estimate project resolves the plan side to an empty materials list (get-or-create),
        // and no reconciled lines ⇒ an empty, non-null matrix, never a 404 (R1.1).
        when(materialsListService.getMaterials(PROJECT_ID, false))
                .thenReturn(materialsList(List.of()));
        when(reconciliationService.list(PROJECT_ID, null, null, null)).thenReturn(List.of());

        MaterialsMatrixDto dto = service.getMatrix(PROJECT_ID);

        assertThat(dto).isNotNull();
        assertThat(dto.projectId()).isEqualTo(PROJECT_ID);
        assertThat(dto.branches()).isEmpty();
        assertThat(dto.grandPlannedBrutto()).isNull();
        assertThat(dto.grandFactBrutto()).isNull();
        assertThat(dto.grandAggregatePercent()).isNull();
    }

    // --- Helpers -------------------------------------------------------------------------------

    private static MatrixRowDto rowOf(MaterialsMatrixDto dto, ConsumptionBranch branch, long materialId) {
        return branchOf(dto, branch).categories().stream()
                .flatMap(c -> c.rows().stream())
                .filter(r -> r.materialId() == materialId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for material " + materialId));
    }

    private static MatrixBranchGroupDto branchOf(MaterialsMatrixDto dto, ConsumptionBranch branch) {
        return dto.branches().stream()
                .filter(b -> b.branch() == branch)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no branch group for " + branch));
    }

    private static MaterialsListDto materialsList(List<MaterialRowDto> rows) {
        // Group the rows by branch the way the FOR-05-05b read model does.
        List<MaterialBranchGroupDto> branches = new java.util.ArrayList<>();
        for (ConsumptionBranch branch : ConsumptionBranch.values()) {
            List<MaterialRowDto> branchRows = rows.stream()
                    .filter(r -> r.branch() == branch)
                    .toList();
            if (!branchRows.isEmpty()) {
                branches.add(new MaterialBranchGroupDto(branch, branchRows));
            }
        }
        return new MaterialsListDto(PROJECT_ID, false, List.of(), branches, List.of(), null, null);
    }

    private static MaterialRowDto plan(long materialId, String name, ConsumptionBranch branch,
                                       String category, String unit, BigDecimal effectiveQty,
                                       BigDecimal brutto) {
        return new MaterialRowDto(materialId, name, materialId, category, branch,
                ConsumptionBasis.PER_UNIT, unit, brutto, brutto, List.of(),
                effectiveQty, BigDecimal.ZERO, effectiveQty,
                new MoneyBrutto(brutto, brutto));
    }

    private static MaterialRowDto planNullPrice(long materialId, String name, ConsumptionBranch branch,
                                                String category, String unit, BigDecimal effectiveQty) {
        // A chosen product with no net price ⇒ MoneyBrutto.EMPTY ⇒ null planned brutto (R1.4, R3.3).
        return new MaterialRowDto(materialId, name, materialId, category, branch,
                ConsumptionBasis.PER_UNIT, unit, null, null, List.of(),
                effectiveQty, BigDecimal.ZERO, effectiveQty, MoneyBrutto.EMPTY);
    }

    private static PurchaseLineServiceModel matched(long lineId, long materialId, MatchTier tier,
                                                    BigDecimal quantity, String unitHint,
                                                    BigDecimal lineTotal) {
        return matchedProducer(lineId, materialId, tier, quantity, unitHint, lineTotal, "Producer");
    }

    private static PurchaseLineServiceModel matchedProducer(long lineId, long materialId, MatchTier tier,
                                                            BigDecimal quantity, String unitHint,
                                                            BigDecimal lineTotal, String manufacturer) {
        return new PurchaseLineServiceModel(
                lineId, 900L, "Supplier", OffsetDateTime.now(), (int) lineId,
                "raw-" + lineId, quantity, unitHint, null, lineTotal, manufacturer,
                materialId, "Material-" + materialId, tier,
                com.foremen.service.model.mapper.ProcurementDocumentServiceMapper.tierPercent(tier),
                MatchStatus.AUTO, null, null);
    }

    private static PurchaseLineServiceModel unmatched(long lineId) {
        return new PurchaseLineServiceModel(
                lineId, 900L, "Supplier", OffsetDateTime.now(), (int) lineId,
                "raw-" + lineId, bd("1"), "kg", null, bd("50"), "Producer",
                null, null, MatchTier.NONE, 0, MatchStatus.UNMATCHED, null, null);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
