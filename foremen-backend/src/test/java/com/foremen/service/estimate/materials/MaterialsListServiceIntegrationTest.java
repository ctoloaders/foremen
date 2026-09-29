package com.foremen.service.estimate.materials;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.CurrencyDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineRoomQtyDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.VatRateDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.MaterialsReserveMap;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.EstimateService;
import com.foremen.service.estimate.EstimateAssignmentService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Spring integration tests for {@link MaterialsListService} — the Materials tab read model + the
 * single reserve-map write (FOR-05-05b, design §B3, task 5.4). They exercise the real Spring-wired
 * service against a real PostgreSQL (Testcontainers), driving the read/write entry points end-to-end
 * through the actual DAOs, the shipped kosztorys assembler, the JSONB reserve-map column, and the
 * {@link DraftGateGuard}.
 *
 * <p>Boot harness mirrors the repo's established estimate integration tests
 * ({@code EstimateAssignmentServiceIntegrationTest}): {@code @SpringBootTest(MOCK)} +
 * {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")}, where Liquibase is disabled
 * and Hibernate {@code create-drop} builds the schema, so every fixture row is created
 * programmatically. Service methods run in their own transactions; assertions read entities back
 * inside a {@link TransactionTemplate} so lazy collections initialise.
 *
 * <p><b>Seeding a concrete material.</b> A row appears on the Materials tab only for a chosen concrete
 * product, so each fixture assigns the work to a room (which seeds a construction + finishing material
 * placeholder line) and then {@code chooseConcrete}s the construction line — collapsing it to the
 * product {@code retailNet} — via {@link EstimateAssignmentService}. The estimate carries a 23% VAT
 * rate so the net→brutto derivation is exercised with a non-trivial rate.
 *
 * <p><b>Repeatability.</b> Every fixture row carries a unique per-run id (UUID substring) and
 * {@link #cleanUp()} removes every row this test created (deepest-FK-first) after each test, so the
 * suite re-runs without manual DB cleanup.
 *
 * <p><b>Validates: Requirements 1.x, 2.x, 4.2, 4.3, 6.x, 10.2, 10.3</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class MaterialsListServiceIntegrationTest {

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
    private MaterialsListService materialsListService;

    @Autowired
    private EstimateAssignmentService assignmentService;

    @Autowired
    private EstimateService estimateService;

    @Autowired
    private EstimateDao estimateDao;

    @Autowired
    private ProjectDao projectDao;
    @Autowired
    private RoomDao roomDao;
    @Autowired
    private RoomTypeDao roomTypeDao;
    @Autowired
    private CurrencyDao currencyDao;
    @Autowired
    private VatRateDao vatRateDao;
    @Autowired
    private MeasurementUnitDao measurementUnitDao;
    @Autowired
    private WorkCategoryDao workCategoryDao;
    @Autowired
    private WorkItemDao workItemDao;
    @Autowired
    private WorkPriceDao workPriceDao;
    @Autowired
    private WorkMaterialConsumptionDao workMaterialConsumptionDao;
    @Autowired
    private ConstructionMaterialTypeDao constructionMaterialTypeDao;
    @Autowired
    private ConstructionMaterialDao constructionMaterialDao;
    @Autowired
    private EstimateLineRoomQtyDao estimateLineRoomQtyDao;

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
            entityManager.createQuery(
                            "delete from EstimateLineRoomMaterialEntity m where m.roomQty.line.estimate.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from EstimateLineRoomQtyEntity rq where rq.line.estimate.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from EstimateLineEntity l where l.estimate.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from EstimateEntity e where e.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from RoomEntity r where r.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from ProjectEntity pr where pr.name like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from WorkMaterialConsumptionEntity c where c.workItem.id in "
                                    + "(select wi.id from WorkItemEntity wi where wi.workCategory.code like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from WorkPriceEntity wp where wp.workItem.id in "
                                    + "(select wi.id from WorkItemEntity wi where wi.workCategory.code like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from WorkItemEntity wi where wi.id in "
                                    + "(select w.id from WorkItemEntity w where w.workCategory.code like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from WorkCategoryEntity wc where wc.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from ConstructionMaterialEntity cm where cm.type.id in "
                                    + "(select ct.id from ConstructionMaterialTypeEntity ct where ct.code like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from ConstructionMaterialTypeEntity ct where ct.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from RoomTypeEntity rt where rt.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from MeasurementUnitEntity mu where mu.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from VatRateEntity vr where vr.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            // The shared "PLN" currency + "m2" unit fixtures are intentionally NOT deleted (reused).
        });
    }

    // =====================================================================================
    // Tests
    // =====================================================================================

    @Test
    @DisplayName("getMaterials returns the projection over a seeded estimate: one construction row for "
            + "the chosen concrete product, per-room qty + net×VAT brutto, fulfilment passthrough "
            + "(R1.x, R2.x, R6.x)")
    void getMaterialsReturnsProjectionOverSeededEstimate() {
        Fixture f = createFixture();
        seedConcreteConstructionMaterial(f);

        MaterialsListDto dto = materialsListService.getMaterials(f.projectId, true);

        assertThat(dto).as("the read model must be non-null (R1.x)").isNotNull();
        assertThat(dto.projectId()).isEqualTo(f.projectId);
        assertThat(dto.editable()).as("editable is passed through by the caller (R10.3)").isTrue();

        // R2.1: one room column, in the kosztorys stable order.
        assertThat(dto.rooms())
                .as("the matrix must render the project's single room column (R2.1)")
                .extracting(MaterialsRoomColumnDto::id)
                .containsExactly(f.roomId);

        // R1.2/R1.5: exactly one distinct concrete construction row, in the construction branch group.
        MaterialRowDto row = singleConstructionRow(dto);
        assertThat(row.materialId())
                .as("the row is the chosen concrete construction product (R1.2)")
                .isEqualTo(f.constructionMaterialId);
        assertThat(row.netUnitPrice())
                .as("the row net unit price is the chosen product retailNet, verbatim (R2.5, R7.2)")
                .isEqualByComparingTo(f.constructionRetailNet);

        // R2.2/R7.3: the single room cell aggregates the resolved Physical_Quantity (norm 1.5 × Volume
        // 20 = 30) and its brutto is asIsQty × net × (1 + vat/100).
        assertThat(row.cells()).as("one cell per room column").hasSize(1);
        MaterialRoomCellDto cell = row.cells().get(0);
        BigDecimal expectedRoomQty = new BigDecimal("1.5000").multiply(f.roomFloorArea); // 30.0000
        assertThat(cell.quantity())
                .as("the room cell qty is the aggregated resolved Physical_Quantity (R2.2, R7.3)")
                .isEqualByComparingTo(expectedRoomQty);
        assertThat(cell.price().brutto())
                .as("the room cell brutto is qty × net × (1 + vat/100) (R2.5, R7.2)")
                .isEqualByComparingTo(bruttoOf(expectedRoomQty, f.constructionRetailNet, f.vatRate));

        // R3.2/R3.4: no reserve set ⇒ effective = ceil(as-is) for a PER_UNIT row.
        assertThat(row.asIsTotalQty()).isEqualByComparingTo(expectedRoomQty);
        assertThat(row.effectiveTotalQty())
                .as("with no reserve, the effective quantity is ceil(as-is) for a PER_UNIT row (R3.3)")
                .isEqualByComparingTo(expectedRoomQty.setScale(0, java.math.RoundingMode.CEILING));

        // R6.5: fulfilment percentage is the kosztorys fill indicator, passed through verbatim.
        assertThat(dto.fulfilmentPct())
                .as("the fulfilment percentage is passed through from the kosztorys (R6.5)")
                .isNotNull();

        // R6.2/R6.3: the construction branch dashboard and grand total are present and consistent.
        assertThat(dto.branchDashboards())
                .as("a per-branch dashboard aligns with each branch group (R6.2)")
                .hasSameSizeAs(dto.branches());
        assertThat(dto.grandTotal())
                .as("a project-wide grand total is present (R6.3)")
                .isNotNull();
    }

    @Test
    @DisplayName("saveReserveMap on a DRAFT estimate persists the JSONB map and recomputes/stores the "
            + "as-is / effective / brutto totals; the refreshed read model reflects the reserve "
            + "(R4.2, R4.3)")
    void saveReserveMapPersistsJsonbAndRecomputesTotals() {
        Fixture f = createFixture();
        seedConcreteConstructionMaterial(f);

        BigDecimal reservePercent = new BigDecimal("10"); // +10% reserve
        MaterialsReserveRequest request = new MaterialsReserveRequest(List.of(
                new MaterialsReserveRequest.ReserveEntry(f.constructionMaterialId, reservePercent)));

        MaterialsListDto refreshed = materialsListService.saveReserveMap(f.projectId, request);

        // The refreshed read model reflects the reserve (R4.3): effective = ceil(as-is × 1.10).
        BigDecimal asIs = new BigDecimal("1.5000").multiply(f.roomFloorArea); // 30.0000
        BigDecimal expectedEffective = asIs
                .multiply(new BigDecimal("1.10"))
                .setScale(0, java.math.RoundingMode.CEILING); // ceil(33.0) = 33
        MaterialRowDto row = singleConstructionRow(refreshed);
        assertThat(row.reservePercent())
                .as("the refreshed row carries the requested reserve percent (R4.3)")
                .isEqualByComparingTo(reservePercent);
        assertThat(row.effectiveTotalQty())
                .as("the effective quantity is ceil(as-is × (1 + pct/100)) (R4.3)")
                .isEqualByComparingTo(expectedEffective);
        assertThat(row.rowTotalPrice().brutto())
                .as("the row total brutto is recomputed from the effective quantity (R4.3)")
                .isEqualByComparingTo(bruttoOf(expectedEffective, f.constructionRetailNet, f.vatRate));

        // The JSONB column is persisted with the recomputed totals (R4.2).
        tx().executeWithoutResult(status -> {
            EstimateEntity estimate = estimateDao.findByProjectId(f.projectId).orElseThrow();
            MaterialsReserveMap map = estimate.getMaterialsReserveMap();
            assertThat(map).as("the reserve map JSONB column must be persisted (R4.2)").isNotNull();
            assertThat(map.byMaterialId())
                    .as("the persisted map is keyed by the concrete material id (R4.2)")
                    .containsKey(f.constructionMaterialId);
            MaterialsReserveMap.ReserveEntry entry = map.byMaterialId().get(f.constructionMaterialId);
            assertThat(entry.percent())
                    .as("the stored entry carries the reserve percent (R4.2)")
                    .isEqualByComparingTo(reservePercent);
            assertThat(entry.asIsQty())
                    .as("the stored entry caches the recomputed as-is quantity (R4.2, R4.3)")
                    .isEqualByComparingTo(asIs);
            assertThat(entry.effectiveQty())
                    .as("the stored entry caches the recomputed effective quantity (R4.2, R4.3)")
                    .isEqualByComparingTo(expectedEffective);
            assertThat(entry.bruttoTotal())
                    .as("the stored entry caches the recomputed effective brutto total (R4.2, R4.3)")
                    .isEqualByComparingTo(bruttoOf(expectedEffective, f.constructionRetailNet, f.vatRate));
        });

        // A follow-up read reflects the persisted reserve (round-trip, R4.2).
        MaterialsListDto reread = materialsListService.getMaterials(f.projectId, true);
        assertThat(singleConstructionRow(reread).effectiveTotalQty())
                .as("a subsequent read reflects the persisted reserve (R4.2)")
                .isEqualByComparingTo(expectedEffective);
    }

    @Test
    @DisplayName("saveReserveMap on a non-DRAFT estimate is rejected with 409 error.estimate.locked "
            + "(R10.2)")
    void saveReserveMapOnNonDraftEstimateIsLocked() {
        Fixture f = createFixture();
        seedConcreteConstructionMaterial(f);
        moveEstimatePastDraft(f.projectId, EstimateStatus.APPROVED);

        MaterialsReserveRequest request = new MaterialsReserveRequest(List.of(
                new MaterialsReserveRequest.ReserveEntry(f.constructionMaterialId, new BigDecimal("5"))));

        assertThatThrownBy(() -> materialsListService.saveReserveMap(f.projectId, request))
                .as("a reserve write on a non-DRAFT estimate must be locked (R10.2)")
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus())
                            .as("the lock must be a 409 CONFLICT (R10.2)")
                            .isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessage())
                            .as("the lock must carry the localized error.estimate.locked code (R10.2)")
                            .isEqualTo("error.estimate.locked");
                });

        // The write was rejected: no reserve map was persisted (R10.2).
        tx().executeWithoutResult(status -> {
            EstimateEntity estimate = estimateDao.findByProjectId(f.projectId).orElseThrow();
            assertThat(estimate.getMaterialsReserveMap())
                    .as("a locked write must persist no reserve map (R10.2)")
                    .isNull();
        });
    }

    @Test
    @DisplayName("getMaterials reads the projection regardless of the estimate lifecycle — an "
            + "APPROVED (non-DRAFT) estimate still renders its matrix (R10.3)")
    void getMaterialsReadsRegardlessOfLifecycle() {
        Fixture f = createFixture();
        seedConcreteConstructionMaterial(f);
        moveEstimatePastDraft(f.projectId, EstimateStatus.APPROVED);

        // The read is independent of DRAFT (R10.3): it still returns the full projection.
        MaterialsListDto dto = materialsListService.getMaterials(f.projectId, false);

        assertThat(dto).as("the read model must render for a non-DRAFT estimate (R10.3)").isNotNull();
        assertThat(dto.editable())
                .as("a non-DRAFT read is passed editable=false by the caller (R10.3)")
                .isFalse();
        MaterialRowDto row = singleConstructionRow(dto);
        assertThat(row.materialId())
                .as("the concrete material row still renders regardless of lifecycle (R10.3)")
                .isEqualTo(f.constructionMaterialId);
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    /** The single construction-branch material row of the read model, failing if not exactly one. */
    private MaterialRowDto singleConstructionRow(MaterialsListDto dto) {
        List<MaterialRowDto> rows = dto.branches().stream()
                .filter(b -> b.branch() == ConsumptionBranch.construction)
                .flatMap(b -> b.rows().stream())
                .toList();
        assertThat(rows)
                .as("exactly one distinct concrete construction row is expected")
                .hasSize(1);
        return rows.get(0);
    }

    /** {@code qty × net × (1 + vat/100)} — the read model's net→brutto derivation (R2.5, R7.2). */
    private static BigDecimal bruttoOf(BigDecimal qty, BigDecimal net, BigDecimal vatRatePct) {
        BigDecimal bruttoUnit = net.multiply(BigDecimal.ONE.add(vatRatePct.movePointLeft(2)));
        return qty.multiply(bruttoUnit);
    }

    /**
     * Assigns the fixture work to the fixture room (seeding a construction + finishing placeholder
     * line) and chooses the fixture concrete construction product on the construction line, collapsing
     * it to the product {@code retailNet} so a concrete material row appears on the Materials tab.
     */
    private void seedConcreteConstructionMaterial(Fixture f) {
        EstimateLineRoomQtyEntity roomQty = assignmentService.assign(f.projectId, f.workItemId, f.roomId, null);
        Long constructionLineId = tx().execute(status -> {
            EstimateLineRoomQtyEntity rq = estimateLineRoomQtyDao.findById(roomQty.getId()).orElseThrow();
            return rq.getMaterials().stream()
                    .filter(m -> m.getBranch() == ConsumptionBranch.construction)
                    .map(EstimateLineRoomMaterialEntity::getId)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("a construction material line must have been seeded"));
        });
        assignmentService.chooseConcrete(f.projectId, constructionLineId, f.constructionMaterialId);
    }

    /** Moves the project's estimate past DRAFT to the given status, so the DRAFT gate rejects writes. */
    private void moveEstimatePastDraft(Long projectId, EstimateStatus status) {
        tx().executeWithoutResult(txStatus -> {
            EstimateEntity estimate = estimateDao.findByProjectId(projectId).orElseThrow();
            estimate.setStatus(status);
            entityManager.merge(estimate);
        });
    }

    // =====================================================================================
    // Fixture building (mirrors EstimateAssignmentServiceIntegrationTest)
    // =====================================================================================

    /** The ids a test needs, captured after the fixture graph is committed. */
    private static final class Fixture {
        Long projectId;
        Long roomId;
        Long workItemId;
        Long constructionMaterialId;
        BigDecimal roomFloorArea;
        BigDecimal constructionRetailNet;
        BigDecimal vatRate;
    }

    /**
     * Builds a self-contained estimate graph in one transaction: a DRAFT project + estimate (with a
     * 23% VAT rate), a room with a known floorArea, an {@code m2}-unit work item with a construction +
     * finishing consumption (so assign seeds two material lines), a WorkPrice, and one active priced
     * construction material (so the chosen concrete's frozen net is non-null).
     */
    private Fixture createFixture() {
        Fixture f = new Fixture();
        f.vatRate = new BigDecimal("23.00");
        tx().executeWithoutResult(status -> {
            CurrencyEntity currency = persistCurrency();
            MeasurementUnitEntity m2Unit = persistMeasurementUnit("m2");
            VatRateEntity vatRate = persistVatRate(f.vatRate);

            WorkCategoryEntity category = persistWorkCategory();
            WorkItemEntity workItem = persistWorkItem("W_" + runId, category, m2Unit);
            persistWorkPrice(workItem, currency, new BigDecimal("150.00"));

            ConstructionMaterialTypeEntity constructionType = persistConstructionType("CT_" + runId);
            BigDecimal constructionRetailNet = new BigDecimal("42.00");
            ConstructionMaterialEntity constructionMaterial =
                    persistConstructionMaterial(constructionType, m2Unit, currency, constructionRetailNet);
            persistConstructionConsumption(workItem, constructionType, m2Unit);

            ProjectEntity project = persistProject();
            RoomTypeEntity roomType = persistRoomType();
            BigDecimal floorArea = new BigDecimal("20.00");
            RoomEntity room = persistRoom(project, roomType, floorArea);

            EstimateEntity estimate = estimateService.getOrCreateEntityForProject(project.getId());
            estimate.setVatRate(vatRate);
            entityManager.merge(estimate);

            f.projectId = project.getId();
            f.roomId = room.getId();
            f.workItemId = workItem.getId();
            f.constructionMaterialId = constructionMaterial.getId();
            f.roomFloorArea = floorArea;
            f.constructionRetailNet = constructionRetailNet;
        });
        return f;
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

    private VatRateEntity persistVatRate(BigDecimal rate) {
        VatRateEntity vatRate = new VatRateEntity();
        vatRate.setCode("VAT_" + runId);
        vatRate.setRate(rate);
        vatRate.setNameRU("НДС " + runId);
        vatRate.setNamePL("VAT " + runId);
        vatRate.setActive(true);
        return vatRateDao.save(vatRate);
    }

    /**
     * Get-or-creates a measurement unit by its <b>exact</b> code (e.g. {@code m2}). The code is NOT
     * suffixed with the run id because {@code VolumeFallbackResolver} maps by the exact (normalized)
     * unit code — the work must carry the literal {@code m2} unit for the floorArea fallback to
     * resolve. It is a get-or-create so it survives across the class's tests and is intentionally left
     * out of {@link #cleanUp()} for the same reason.
     */
    private MeasurementUnitEntity persistMeasurementUnit(String code) {
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

    private WorkCategoryEntity persistWorkCategory() {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setCode("CAT_" + runId);
        category.setOrderNo(1);
        category.setNameRU("Категория " + runId);
        category.setNamePL("Kategoria " + runId);
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

    private WorkPriceEntity persistWorkPrice(WorkItemEntity workItem, CurrencyEntity currency, BigDecimal netPrice) {
        WorkPriceEntity workPrice = new WorkPriceEntity();
        workPrice.setWorkItem(workItem);
        workPrice.setCurrency(currency);
        workPrice.setNetPrice(netPrice);
        return workPriceDao.save(workPrice);
    }

    private ConstructionMaterialTypeEntity persistConstructionType(String code) {
        ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
        type.setCode(code);
        type.setNameRU("Тип " + runId);
        type.setNamePL("Typ " + runId);
        type.setActive(true);
        return constructionMaterialTypeDao.save(type);
    }

    private ConstructionMaterialEntity persistConstructionMaterial(
            ConstructionMaterialTypeEntity type, MeasurementUnitEntity unit,
            CurrencyEntity currency, BigDecimal retailNet) {
        ConstructionMaterialEntity material = new ConstructionMaterialEntity();
        material.setNameRU("Материал " + runId);
        material.setNamePL("Materiał " + runId);
        material.setType(type);
        material.setUnit(unit);
        material.setCurrency(currency);
        material.setRetailNet(retailNet);
        material.setActive(true);
        return constructionMaterialDao.save(material);
    }

    private void persistConstructionConsumption(
            WorkItemEntity workItem, ConstructionMaterialTypeEntity type, MeasurementUnitEntity materialUnit) {
        WorkMaterialConsumptionEntity consumption = new WorkMaterialConsumptionEntity();
        consumption.setWorkItem(workItem);
        consumption.setMaterialUnit(materialUnit);
        consumption.setBranch(ConsumptionBranch.construction);
        consumption.setConstructionMaterialType(type);
        consumption.setNormQty(new BigDecimal("1.5000"));
        consumption.setConsumptionBasis(ConsumptionBasis.PER_UNIT);
        consumption.setSourceType("MANUAL");
        consumption.setSourceDoc("test");
        consumption.setSourceRef("test");
        workMaterialConsumptionDao.save(consumption);
    }

    private ProjectEntity persistProject() {
        ProjectEntity project = new ProjectEntity();
        project.setName("Materials IT Project " + runId + "-" + COUNTER.incrementAndGet());
        project.setStatus(ProjectStatus.DRAFT);
        return projectDao.save(project);
    }

    private RoomTypeEntity persistRoomType() {
        RoomTypeEntity roomType = new RoomTypeEntity();
        roomType.setCode("ROOMTYPE_" + runId);
        roomType.setNameRU("Комната " + runId);
        roomType.setNamePL("Pokój " + runId);
        roomType.setActive(true);
        return roomTypeDao.save(roomType);
    }

    private RoomEntity persistRoom(ProjectEntity project, RoomTypeEntity roomType, BigDecimal floorArea) {
        RoomEntity room = new RoomEntity();
        room.setProject(project);
        room.setRoomType(roomType);
        room.setLabel("Room " + runId);
        room.setFloorArea(floorArea);
        return roomDao.save(room);
    }
}
