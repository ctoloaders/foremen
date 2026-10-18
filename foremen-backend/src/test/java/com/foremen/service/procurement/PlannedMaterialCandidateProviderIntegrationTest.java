package com.foremen.service.procurement;

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
import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineRoomQtyDao;
import com.foremen.dao.MaterialProducerDao;
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
import com.foremen.dao.model.MaterialProducerEntity;
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
import com.foremen.service.EstimateService;
import com.foremen.service.estimate.EstimateAssignmentService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-06-03 task 9.2 — Spring integration test for {@link PlannedMaterialCandidateProvider}
 * (Requirements 4.1, 4.3, 4.4). It exercises the real Spring-wired provider over the existing
 * FOR-05-05b concrete-material projection against a real PostgreSQL (Testcontainers), proving the
 * two behaviours the provider owns:
 *
 * <ol>
 *   <li><b>{@code ru → pl → code} resolution over the seeded projection</b> (R4.3, R4.4): a chosen
 *       concrete construction material, its {@code producer}, and its material {@code type} are
 *       read back through the provider with each of the three match dimensions resolved
 *       {@code ru → pl → code}: the producer's {@code nameRU} becomes the candidate
 *       {@code manufacturer}, the type's {@code nameRU} becomes the {@code category}, and the
 *       material's {@code [nameRU, namePL]} become the candidate {@code names}. The candidate set
 *       is read <em>only</em> from the FOR-05-05b projection (R4.3) — no second material list.</li>
 *   <li><b>The empty-set case</b> (R4.2/R4.3 boundary): a project with an estimate but <em>no</em>
 *       chosen concrete materials yields an empty candidate list, never an error.</li>
 * </ol>
 *
 * <p>The OCR seams ({@code OcrService}) are irrelevant here and are <b>not</b> invoked — the
 * candidate provider performs a pure read over the materials projection, so no external service is
 * called. (The match-engine, OCR-helper, service, and ABAC property tests live in 3.1a / 3.2a /
 * 5.1a / 6.2a and are not duplicated.)
 *
 * <p>Boot harness mirrors {@code MaterialsListServiceIntegrationTest}: {@code @SpringBootTest(MOCK)}
 * + {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")}, where Liquibase is
 * disabled and Hibernate {@code create-drop} builds the schema, so every fixture row is created
 * programmatically. A chosen concrete construction material appears on the Materials-tab projection
 * only after the work is assigned to a room (seeding a construction + finishing placeholder line)
 * and the construction line is {@code chooseConcrete}d — exactly as the sibling materials test seeds
 * it. Every fixture row carries a unique per-run id and {@link #cleanUp()} removes it, so the suite
 * re-runs without manual DB cleanup.
 *
 * <p><b>Validates: Requirements 4.1, 4.3, 4.4</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class PlannedMaterialCandidateProviderIntegrationTest {

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
    private PlannedMaterialCandidateProvider candidateProvider;

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
    private MaterialProducerDao materialProducerDao;
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
            entityManager.createQuery("delete from MaterialProducerEntity mp where mp.code like :p")
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
    @DisplayName("candidatesFor returns one PlannedMaterial per chosen concrete material over the "
            + "FOR-05-05b projection, with manufacturer/category/names resolved ru → pl → code "
            + "(R4.1, R4.3, R4.4)")
    void candidatesForResolvesRuPlCodeOverProjection() {
        Fixture f = createFixture();
        seedConcreteConstructionMaterial(f);

        List<PlannedMaterial> candidates = candidateProvider.candidatesFor(f.projectId);

        // R4.1/R4.3: exactly one candidate — the single distinct chosen concrete material from the
        // FOR-05-05b projection (no second material list).
        assertThat(candidates)
                .as("one candidate per distinct chosen concrete material, read from the FOR-05-05b "
                        + "projection (R4.1, R4.3)")
                .hasSize(1);

        PlannedMaterial candidate = candidates.get(0);

        // The candidate carries the row's concrete material id (the soft FK the matched line stores).
        assertThat(candidate.materialId())
                .as("the candidate materialId is the chosen concrete material id (R4.1)")
                .isEqualTo(f.constructionMaterialId);

        // R4.4: manufacturer = producer, resolved ru → pl → code. The producer carries nameRU, so RU
        // wins over PL and code.
        assertThat(candidate.manufacturer())
                .as("manufacturer is the producer nameRU (ru wins the ru → pl → code fallback, R4.4)")
                .isEqualTo(f.producerNameRu);

        // R4.4: category = material type, resolved ru → pl → code. The type carries nameRU, so RU wins.
        assertThat(candidate.category())
                .as("category is the material type nameRU (ru wins the ru → pl → code fallback, R4.4)")
                .isEqualTo(f.constructionTypeNameRu);

        // R4.4: names = [nameRU, namePL] of the concrete material, both non-blank here.
        assertThat(candidate.names())
                .as("names are the material's localized [nameRU, namePL] (R4.4)")
                .containsExactly(f.constructionMaterialNameRu, f.constructionMaterialNamePl);
    }

    @Test
    @DisplayName("candidatesFor returns an empty set (never an error) for a project with an estimate "
            + "but no chosen concrete materials (R4.2 boundary)")
    void candidatesForReturnsEmptySetWhenNoChosenConcreteMaterials() {
        // A project + estimate exist, but NO work is assigned and NO concrete material is chosen, so
        // the FOR-05-05b projection yields no construction/finishing rows.
        Fixture f = createFixture();
        // Ensure the estimate row exists so the projection is a real (empty) read, not a null guard.
        tx().executeWithoutResult(status -> estimateService.getOrCreateEntityForProject(f.projectId));

        List<PlannedMaterial> candidates = candidateProvider.candidatesFor(f.projectId);

        assertThat(candidates)
                .as("a project with no chosen concrete materials yields an empty candidate set "
                        + "without error (R4.2)")
                .isNotNull()
                .isEmpty();
    }

    // =====================================================================================
    // Fixture helpers (mirror MaterialsListServiceIntegrationTest)
    // =====================================================================================

    /**
     * Assigns the fixture work to the fixture room (seeding a construction + finishing placeholder
     * line) and chooses the fixture concrete construction product on the construction line, so a
     * concrete material row appears on the FOR-05-05b Materials projection.
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

    /** The ids a test needs, captured after the fixture graph is committed. */
    private static final class Fixture {
        Long projectId;
        Long roomId;
        Long workItemId;
        Long constructionMaterialId;
        String producerNameRu;
        String constructionTypeNameRu;
        String constructionMaterialNameRu;
        String constructionMaterialNamePl;
    }

    /**
     * Builds a self-contained estimate graph in one transaction: a DRAFT project + estimate (23% VAT),
     * a room with a known floorArea, an {@code m2}-unit work item with a construction consumption (so
     * assign seeds a construction material line), a WorkPrice, and one active priced construction
     * material carrying a {@code producer} with distinct RU/PL/code and a type with distinct RU/PL/code
     * so the {@code ru → pl → code} resolution is observable.
     */
    private Fixture createFixture() {
        Fixture f = new Fixture();
        f.producerNameRu = "Производитель " + runId;
        f.constructionTypeNameRu = "Тип " + runId;
        f.constructionMaterialNameRu = "Материал " + runId;
        f.constructionMaterialNamePl = "Materiał " + runId;
        tx().executeWithoutResult(status -> {
            CurrencyEntity currency = persistCurrency();
            MeasurementUnitEntity m2Unit = persistMeasurementUnit("m2");
            VatRateEntity vatRate = persistVatRate(new BigDecimal("23.00"));

            WorkCategoryEntity category = persistWorkCategory();
            WorkItemEntity workItem = persistWorkItem("W_" + runId, category, m2Unit);
            persistWorkPrice(workItem, currency, new BigDecimal("150.00"));

            MaterialProducerEntity producer = persistProducer(f.producerNameRu);
            ConstructionMaterialTypeEntity constructionType =
                    persistConstructionType("CT_" + runId, f.constructionTypeNameRu);
            ConstructionMaterialEntity constructionMaterial = persistConstructionMaterial(
                    constructionType, producer, m2Unit, currency,
                    f.constructionMaterialNameRu, f.constructionMaterialNamePl);
            persistConstructionConsumption(workItem, constructionType, m2Unit);

            ProjectEntity project = persistProject();
            RoomTypeEntity roomType = persistRoomType();
            RoomEntity room = persistRoom(project, roomType, new BigDecimal("20.00"));

            EstimateEntity estimate = estimateService.getOrCreateEntityForProject(project.getId());
            estimate.setVatRate(vatRate);
            entityManager.merge(estimate);

            f.projectId = project.getId();
            f.roomId = room.getId();
            f.workItemId = workItem.getId();
            f.constructionMaterialId = constructionMaterial.getId();
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

    private MaterialProducerEntity persistProducer(String nameRu) {
        MaterialProducerEntity producer = new MaterialProducerEntity();
        producer.setCode("PROD_" + runId);
        producer.setNameRU(nameRu);
        producer.setNamePL("Producent " + runId);
        producer.setActive(true);
        return materialProducerDao.save(producer);
    }

    private ConstructionMaterialTypeEntity persistConstructionType(String code, String nameRu) {
        ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
        type.setCode(code);
        type.setNameRU(nameRu);
        type.setNamePL("Typ " + runId);
        type.setActive(true);
        return constructionMaterialTypeDao.save(type);
    }

    private ConstructionMaterialEntity persistConstructionMaterial(
            ConstructionMaterialTypeEntity type, MaterialProducerEntity producer,
            MeasurementUnitEntity unit, CurrencyEntity currency, String nameRu, String namePl) {
        ConstructionMaterialEntity material = new ConstructionMaterialEntity();
        material.setNameRU(nameRu);
        material.setNamePL(namePl);
        material.setType(type);
        material.setProducer(producer);
        material.setUnit(unit);
        material.setCurrency(currency);
        material.setRetailNet(new BigDecimal("42.00"));
        material.setCostNet(new BigDecimal("38.00"));
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
        project.setName("Procurement Candidate IT Project " + runId + "-" + COUNTER.incrementAndGet());
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
