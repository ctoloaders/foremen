package com.foremen.service.estimate.margins;

import java.math.BigDecimal;
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

import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.CurrencyDao;
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
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
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
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.EstimateService;
import com.foremen.service.estimate.EstimateAssignmentService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-05-06 (task 5.2) — Spring integration tests for {@link MarginsListService} + the Margins tab
 * read model over a real estimate. They exercise the real Spring-wired service against a real
 * PostgreSQL (Testcontainers), driving {@link MarginsListService#getMargins(Long)} end-to-end through
 * the shipped estimate get-or-create path, the kosztorys {@code EstimateMatrixAssembler}, the
 * WorkerType dictionary + material {@code cost_net} lookups, and the pure {@link MarginsListAssembler}.
 *
 * <p>Boot harness mirrors {@code MaterialsListServiceIntegrationTest}:
 * {@code @SpringBootTest(MOCK)} + {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")}
 * where Liquibase is disabled and Hibernate {@code create-drop} builds the schema — so the {@code 125}
 * WorkerType seed does <em>not</em> pre-populate the tiers; this test seeds its own base + non-base
 * tiers (mirroring {@code WorkerTypeControllerIntegrationTest}, which likewise seeds its own base
 * rows). Service methods run in their own transactions; assertions read the returned read model.
 *
 * <p><b>What is asserted (R4.1, R4.2, R5.1):</b>
 * <ul>
 *   <li><b>Rows grouped by work type.</b> The read model groups the seeded work under its work
 *       category, in kosztorys order (R4.2, R4.7).</li>
 *   <li><b>Per-tier costs.</b> Each row carries one {@link TierCostDto} per seeded WorkerType tier —
 *       {@code Base_Cost = round(0.40 × offer, 0.5)} and {@code firmCost = base × (1 + 0.65)} (R2.1,
 *       R2.2, R4.3).</li>
 *   <li><b>Per-branch material margins.</b> The construction branch material margin is
 *       {@code retail − cost} over the chosen concrete product's {@code retailNet}/{@code cost_net},
 *       kept separate from labour (decision #4, R4.4).</li>
 *   <li><b>Pre-estimate project ⇒ empty, not 404.</b> A project with no assigned works returns a
 *       200-shaped empty read model (empty groups, tiers present), not a {@code 404}, because the
 *       service reuses the kosztorys get-or-create path (design "Error Handling").</li>
 * </ul>
 *
 * <p><b>Repeatability.</b> Every fixture row carries a unique per-run id (UUID substring) and
 * {@link #cleanUp()} removes every row this test created (deepest-FK-first) after each test, so the
 * suite re-runs without manual DB cleanup.
 *
 * <p>Validates: Requirements 4.1, 4.2, 5.1
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class MarginsListServiceIntegrationTest {

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
    private MarginsListService marginsListService;

    @Autowired
    private EstimateAssignmentService assignmentService;

    @Autowired
    private EstimateService estimateService;

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
    private WorkerTypeDao workerTypeDao;

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
            entityManager.createQuery("delete from WorkerTypeEntity w where w.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            // The shared "PLN" currency + "m2" unit fixtures are intentionally NOT deleted (reused).
        });
    }

    // =====================================================================================
    // Tests
    // =====================================================================================

    @Test
    @DisplayName("getMargins returns the projection over a seeded estimate: work grouped by work type, "
            + "per-tier costs (base + firm uplift), per-branch construction material margin (R4.1, R4.2, "
            + "R5.1)")
    void getMarginsReturnsProjectionOverSeededEstimate() {
        seedWorkerTiers();
        Fixture f = createFixture();
        seedConcreteConstructionMaterial(f);

        MarginsListDto dto = marginsListService.getMargins(f.projectId);

        assertThat(dto).as("the read model must be non-null (R4.1)").isNotNull();
        assertThat(dto.projectId()).isEqualTo(f.projectId);

        // R4.3: the ordered tier headers are the seeded BASE + FIRM tiers.
        assertThat(dto.workerTypes())
                .as("the read model exposes the ordered WorkerType tier headers (R4.3)")
                .hasSize(2);
        assertThat(dto.workerTypes().get(0).base())
                .as("the first tier header is the base tier (order_no 1)")
                .isTrue();
        assertThat(dto.workerTypes().get(1).base())
                .as("the second tier header is the non-base FIRM tier")
                .isFalse();

        // R4.2/R4.7: the work is grouped under its work category.
        assertThat(dto.groups())
                .as("the matrix groups rows by work type (R4.2, R4.7)")
                .hasSize(1);
        MarginWorkGroupDto group = dto.groups().get(0);
        assertThat(group.categoryId())
                .as("the group is keyed by the seeded work category (R4.7)")
                .isEqualTo(f.workCategoryId);
        assertThat(group.rows())
                .as("the group contains the single assigned work row (R4.2)")
                .hasSize(1);

        MarginRowDto row = group.rows().get(0);
        assertThat(row.workItemId())
                .as("the row is the assigned work item")
                .isEqualTo(f.workItemId);

        // R2.1/R4.3: the work offer labour = unitPrice × Volume, where Volume is the room work
        // volume (the m2 floorArea, 20), so offer = 150 × 20 = 3000. The material quantity applies the
        // consumption norm on top of that Volume (norm 1.5 × Volume 20 = 30), so material qty ≠ offer
        // Volume.
        BigDecimal offerVolume = f.roomFloorArea;                          // 20.00 (work Volume)
        BigDecimal materialQty = new BigDecimal("1.5000").multiply(offerVolume); // 30.0000
        BigDecimal expectedOffer = f.workNetPrice.multiply(offerVolume);   // 3000.00
        assertThat(row.offerPrice())
                .as("the offer is Σ cell labour = unitPrice × Volume (R4.3)")
                .isEqualByComparingTo(expectedOffer);
        BigDecimal expectedBase = MarginCostService.baseCost(expectedOffer, new BigDecimal("0.40"));
        assertThat(row.baseCost())
                .as("Base_Cost = round(0.40 × offer to 0.5 zł) (R2.1)")
                .isEqualByComparingTo(expectedBase);

        // R4.3: one tier cost per seeded tier; base cost == base, firm == base × (1 + 0.65).
        assertThat(row.tierCosts())
                .as("one Tier_Cost per seeded WorkerType tier (R4.3)")
                .hasSize(2);
        TierCostDto baseTier = row.tierCosts().get(0);
        TierCostDto firmTier = row.tierCosts().get(1);
        assertThat(baseTier.cost())
                .as("the base tier cost equals Base_Cost (uplift 0, R2.2)")
                .isEqualByComparingTo(expectedBase);
        assertThat(firmTier.cost())
                .as("the firm tier cost = Base_Cost × (1 + 0.65) (R2.2)")
                .isEqualByComparingTo(expectedBase.multiply(new BigDecimal("1.65")));

        // R4.4 (labour margin, per tier): offer − tierCost, kept separate from material.
        assertThat(baseTier.labourMargin().amount())
                .as("the base-tier labour margin is offer − baseCost (R4.4)")
                .isEqualByComparingTo(expectedOffer.subtract(expectedBase));

        // R4.4/R5.4 (per-branch material margin): construction retail/cost/margin over the chosen
        // product, using the material quantity (norm × Volume = 30).
        BigDecimal expectedRetail = materialQty.multiply(f.constructionRetailNet);
        BigDecimal expectedCost = materialQty.multiply(f.constructionCostNet);
        BranchMaterialDto construction = row.construction();
        assertThat(construction.retailTotal())
                .as("the construction branch retail = Σ qty × retailNet (R4.4)")
                .isEqualByComparingTo(expectedRetail);
        assertThat(construction.costTotal())
                .as("the construction branch cost = Σ qty × cost_net (R4.4)")
                .isEqualByComparingTo(expectedCost);
        assertThat(construction.margin().amount())
                .as("the construction material margin = retail − cost, kept separate from labour (decision #4, R4.4)")
                .isEqualByComparingTo(expectedRetail.subtract(expectedCost));

        // R5: the dashboard folds the per-tier labour totals over the priced row.
        assertThat(dto.dashboard()).as("the cost dashboard is present (R5.1)").isNotNull();
        assertThat(dto.dashboard().labourByTier())
                .as("one per-tier labour total per seeded tier (R5.2)")
                .hasSize(2);
        assertThat(dto.dashboard().labourByTier().get(0).offerTotal())
                .as("the base-tier dashboard offer total = Σ priced-row offer (Property 9)")
                .isEqualByComparingTo(expectedOffer);
    }

    @Test
    @DisplayName("getMargins on a pre-estimate project returns an empty structure (200-shaped, empty "
            + "groups), NOT a 404 — the service reuses the kosztorys get-or-create path (R4.1)")
    void getMarginsOnPreEstimateProjectReturnsEmptyNotNotFound() {
        seedWorkerTiers();
        // A project with NO assigned works and NO estimate row yet. The default PLN currency must
        // exist because the get-or-create path defaults the new estimate's currency to PLN.
        Long projectId = tx().execute(status -> {
            persistCurrency();
            return persistProject().getId();
        });

        MarginsListDto dto = marginsListService.getMargins(projectId);

        assertThat(dto)
                .as("a pre-estimate project must return an empty read model, not a 404 (R4.1, Error Handling)")
                .isNotNull();
        assertThat(dto.projectId()).isEqualTo(projectId);
        assertThat(dto.groups())
                .as("a pre-estimate project has no work rows ⇒ empty groups (R4.1)")
                .isEmpty();
        assertThat(dto.workerTypes())
                .as("the tier headers are still present even with no work rows (R4.3)")
                .hasSize(2);
        assertThat(dto.dashboard())
                .as("the dashboard is present (empty folds) for a pre-estimate project (R5.1)")
                .isNotNull();
        assertThat(dto.dashboard().labourByTier())
                .as("the per-tier dashboard still lists every tier (empty totals) (R5.2)")
                .hasSize(2);
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    /**
     * Assigns the fixture work to the fixture room (seeding a construction placeholder line) and
     * chooses the fixture concrete construction product, collapsing it to the product {@code retailNet}
     * so a per-branch construction material figure appears on the Margins tab.
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

    /** Seeds a BASE (0.40 share) tier + a non-base FIRM (+0.65 uplift) tier for the run. */
    private void seedWorkerTiers() {
        tx().executeWithoutResult(status -> {
            persistWorkerType("WT_BASE_" + runId, new BigDecimal("0.4000"), true, 1, "База", "Baza");
            persistWorkerType("WT_FIRM_" + runId, new BigDecimal("0.6500"), false, 2, "Фирма", "Firma");
        });
    }

    private WorkerTypeEntity persistWorkerType(
            String code, BigDecimal tierPct, boolean base, int orderNo, String nameRU, String namePL) {
        WorkerTypeEntity entity = new WorkerTypeEntity();
        entity.setCode(code);
        entity.setTierPct(tierPct);
        entity.setBase(base);
        entity.setOrderNo(orderNo);
        entity.setNameRU(nameRU);
        entity.setNamePL(namePL);
        entity.setActive(true);
        return workerTypeDao.save(entity);
    }

    // =====================================================================================
    // Fixture building (mirrors MaterialsListServiceIntegrationTest)
    // =====================================================================================

    /** The ids a test needs, captured after the fixture graph is committed. */
    private static final class Fixture {
        Long projectId;
        Long roomId;
        Long workItemId;
        Long workCategoryId;
        Long constructionMaterialId;
        BigDecimal roomFloorArea;
        BigDecimal constructionRetailNet;
        BigDecimal constructionCostNet;
        BigDecimal workNetPrice;
    }

    /**
     * Builds a self-contained estimate graph in one transaction: a DRAFT project + estimate (with a
     * 23% VAT rate), a room with a known floorArea, an {@code m2}-unit work item with a construction
     * consumption (so assign seeds a construction material line), a WorkPrice, and one active priced
     * construction material carrying both a retail net and a self-cost {@code cost_net} (so the
     * per-branch material margin is non-trivial).
     */
    private Fixture createFixture() {
        Fixture f = new Fixture();
        tx().executeWithoutResult(status -> {
            CurrencyEntity currency = persistCurrency();
            MeasurementUnitEntity m2Unit = persistMeasurementUnit("m2");
            VatRateEntity vatRate = persistVatRate(new BigDecimal("23.00"));

            WorkCategoryEntity category = persistWorkCategory();
            WorkItemEntity workItem = persistWorkItem("W_" + runId, category, m2Unit);
            f.workNetPrice = new BigDecimal("150.00");
            persistWorkPrice(workItem, currency, f.workNetPrice);

            ConstructionMaterialTypeEntity constructionType = persistConstructionType("CT_" + runId);
            BigDecimal constructionRetailNet = new BigDecimal("42.00");
            BigDecimal constructionCostNet = new BigDecimal("37.80"); // seeded retail − 10%
            ConstructionMaterialEntity constructionMaterial = persistConstructionMaterial(
                    constructionType, m2Unit, currency, constructionRetailNet, constructionCostNet);
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
            f.workCategoryId = category.getId();
            f.constructionMaterialId = constructionMaterial.getId();
            f.roomFloorArea = floorArea;
            f.constructionRetailNet = constructionRetailNet;
            f.constructionCostNet = constructionCostNet;
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
            CurrencyEntity currency, BigDecimal retailNet, BigDecimal costNet) {
        ConstructionMaterialEntity material = new ConstructionMaterialEntity();
        material.setNameRU("Материал " + runId);
        material.setNamePL("Materiał " + runId);
        material.setType(type);
        material.setUnit(unit);
        material.setCurrency(currency);
        material.setRetailNet(retailNet);
        material.setCostNet(costNet);
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
        project.setName("Margins IT Project " + runId + "-" + COUNTER.incrementAndGet());
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
