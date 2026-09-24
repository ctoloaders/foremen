package com.foremen.service.estimate;

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
import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.EstimateLineRoomQtyDao;
import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MaterialCategoryDao;
import com.foremen.dao.MaterialDao;
import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.EstimateService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Spring integration tests for {@link EstimateAssignmentService} — the matrix write orchestrator
 * (FOR-05-05, design §B4, task 5.7). They exercise the real Spring-wired service against a real
 * PostgreSQL (Testcontainers), driving the persisting entry points end-to-end through the actual
 * DAOs, the frozen-price copy path, the {@code EstimateLineRoomQty}/{@code EstimateLineRoomMaterial}
 * cascade, and {@link EstimateService#getOrCreateForProject} for the DRAFT estimate.
 *
 * <p>Boot harness mirrors the repo's established estimate integration tests
 * ({@code FormulaAssortmentEndToEndIntegrationTest}): {@code @SpringBootTest(MOCK)} +
 * {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")}, where Liquibase is disabled
 * and Hibernate {@code create-drop} builds the schema, so every fixture row is created
 * programmatically. The service methods run in their own transactions; assertions read entities back
 * inside a {@link TransactionTemplate} so lazy collections (room-qtys, material lines) initialise.
 *
 * <p><b>Volume via unit fallback.</b> Fixtures deliberately give the work item the {@code m2} unit
 * and no volume formula, so {@code VolumeResolver} falls back to the room's {@code floorArea}
 * (a known, asserted value) — this keeps the assign fixtures free of a parsed-AST formula while
 * still producing a deterministic per-cell Volume.
 *
 * <p><b>Repeatability.</b> Every fixture row carries a unique per-run id (UUID substring) and
 * {@link #cleanUp()} removes every row this test created (deepest-FK-first) after each test, so the
 * suite re-runs without manual DB cleanup.
 *
 * <p><b>Validates: Requirements 3.1, 6.2, 6.3, 9.3, 19.3</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class EstimateAssignmentServiceIntegrationTest {

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
    private MaterialTypeDao materialTypeDao;
    @Autowired
    private MaterialCategoryDao materialCategoryDao;
    @Autowired
    private MaterialDao materialDao;
    @Autowired
    private FinishingMaterialDao finishingMaterialDao;
    @Autowired
    private EstimateLineRoomQtyDao estimateLineRoomQtyDao;
    @Autowired
    private EstimateLineRoomMaterialDao estimateLineRoomMaterialDao;

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
            // Bulk DELETE predicates use a flat `id in (subselect)` rather than a dotted
            // association path: Hibernate cannot resolve a nested-association alias (e.g.
            // c.workItem.workCategory.code) inside a bulk DELETE's WHERE clause, and — because
            // WorkItemEntity now owns the work_room_types @ManyToMany collection — a dotted-path
            // WorkItemEntity delete also emits an invalid join-table clear. Subselects keep every
            // predicate single-table.
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
            entityManager.createQuery(
                            "delete from FinishingMaterialEntity fm where fm.type.id in "
                                    + "(select mt.id from MaterialTypeEntity mt where mt.code like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from MaterialTypeEntity mt where mt.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from MaterialEntity mm where mm.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from MaterialCategoryEntity mc where mc.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from RoomTypeEntity rt where rt.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from MeasurementUnitEntity mu where mu.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            // The shared "PLN" currency fixture is intentionally NOT deleted (get-or-create, reused).
        });
    }

    // =====================================================================================
    // Tests
    // =====================================================================================

    @Test
    @DisplayName("assign creates the line + room-qty at the resolved Volume and freezes the copied "
            + "material lines (R3.1)")
    void assign_createsLineAndRoomQty() {
        Fixture f = createFixture();

        EstimateLineRoomQtyEntity created = assignmentService.assign(
                f.projectId, f.workItemId, f.roomId, null);
        assertThat(created).as("assign must return the created (line, room) room-qty").isNotNull();

        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity reloaded = estimateLineRoomQtyDao.findById(created.getId())
                    .orElseThrow(() -> new AssertionError("room-qty must exist after assign"));
            EstimateLineEntity line = reloaded.getLine();

            assertThat(line.getWorkItem().getId())
                    .as("the created line must be for the assigned work (R3.1)")
                    .isEqualTo(f.workItemId);
            assertThat(line.getUnitPrice())
                    .as("the line must carry the frozen copied labour price (R3.4/R13.1)")
                    .isEqualByComparingTo(f.workNetPrice);

            List<EstimateLineRoomQtyEntity> roomQtys = line.getRoomQtys();
            assertThat(roomQtys).as("exactly one (line, room) room-qty must exist").hasSize(1);
            EstimateLineRoomQtyEntity rq = roomQtys.get(0);
            assertThat(rq.getRoom().getId())
                    .as("the room-qty must reference the assigned room (R3.1)").isEqualTo(f.roomId);
            assertThat(rq.getQuantity())
                    .as("the Volume must be the room's floorArea via the m2 unit fallback (R3.2/R3.3)")
                    .isEqualByComparingTo(f.roomFloorArea);

            // The seeded material lines: one per consumption type (a construction and a finishing).
            assertThat(rq.getMaterials())
                    .as("assign seeds one frozen material line per consumption type (R3.4)")
                    .hasSize(2);
        });
    }

    @Test
    @DisplayName("addMaterialLine adds a keyed line and removeMaterialLine removes it (R6.2)")
    void materialAddRemove() {
        Fixture f = createFixture();
        EstimateLineRoomQtyEntity roomQty = assignmentService.assign(f.projectId, f.workItemId, f.roomId, null);
        Long roomQtyId = roomQty.getId();

        // A fresh construction type NOT part of the work's consumption -> not seeded on assign.
        Long extraTypeId = tx().execute(status ->
                persistConstructionType("EXTRA_" + runId).getId());

        // Sanity: the extra type has no line yet.
        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity rq = estimateLineRoomQtyDao.findById(roomQtyId).orElseThrow();
            assertThat(hasConstructionLine(rq.getMaterials(), extraTypeId))
                    .as("the extra type must not be present before add").isFalse();
        });

        assignmentService.addMaterialLine(f.projectId, roomQtyId, ConsumptionBranch.construction, extraTypeId);

        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity rq = estimateLineRoomQtyDao.findById(roomQtyId).orElseThrow();
            assertThat(hasConstructionLine(rq.getMaterials(), extraTypeId))
                    .as("addMaterialLine must add the (construction, extra type) line (R6.2)").isTrue();
        });

        assignmentService.removeMaterialLine(f.projectId, roomQtyId, ConsumptionBranch.construction, extraTypeId);

        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity rq = estimateLineRoomQtyDao.findById(roomQtyId).orElseThrow();
            assertThat(hasConstructionLine(rq.getMaterials(), extraTypeId))
                    .as("removeMaterialLine must remove the (construction, extra type) line (R6.2)").isFalse();
        });
    }

    @Test
    @DisplayName("chooseConcrete sets the product and collapses the line to its retailNet (R6.3)")
    void chooseConcrete() {
        Fixture f = createFixture();
        EstimateLineRoomQtyEntity roomQty = assignmentService.assign(f.projectId, f.workItemId, f.roomId, null);

        // Resolve the seeded construction material line's id.
        Long constructionLineId = tx().execute(status -> {
            EstimateLineRoomQtyEntity rq = estimateLineRoomQtyDao.findById(roomQty.getId()).orElseThrow();
            return rq.getMaterials().stream()
                    .filter(m -> m.getBranch() == ConsumptionBranch.construction)
                    .map(EstimateLineRoomMaterialEntity::getId)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("a construction material line must have been seeded"));
        });

        assignmentService.chooseConcrete(f.projectId, constructionLineId, f.constructionMaterialId);

        tx().executeWithoutResult(status -> {
            EstimateLineRoomMaterialEntity line =
                    estimateLineRoomMaterialDao.findById(constructionLineId).orElseThrow();
            assertThat(line.getConcreteConstructionMaterial())
                    .as("chooseConcrete must set the chosen construction product (R6.3)").isNotNull();
            assertThat(line.getConcreteConstructionMaterial().getId()).isEqualTo(f.constructionMaterialId);
            assertThat(line.getConcreteNet())
                    .as("choosing a concrete product collapses the line to its retailNet (R6.3/R6.4)")
                    .isEqualByComparingTo(f.constructionRetailNet);
        });
    }

    @Test
    @DisplayName("bulkChooseConcreteForWork fills a (branch, type) across all of a work's assigned "
            + "cells (R9.3)")
    void bulkChooseConcreteAcrossCells() {
        Fixture f = createFixture();
        // Assign the same work to two rooms so the bulk fill has more than one cell to touch.
        Long roomId2 = tx().execute(status -> persistRoom(
                projectDao.findById(f.projectId).orElseThrow(),
                roomTypeDao.findById(f.roomTypeId).orElseThrow(),
                new BigDecimal("30.00")).getId());

        assignmentService.assign(f.projectId, f.workItemId, f.roomId, null);
        assignmentService.assign(f.projectId, f.workItemId, roomId2, null);

        assignmentService.bulkChooseConcreteForWork(
                f.projectId, f.workItemId, ConsumptionBranch.construction, f.constructionTypeId,
                f.constructionMaterialId);

        tx().executeWithoutResult(status -> {
            List<EstimateLineRoomMaterialEntity> constructionLines =
                    entityManager.createQuery(
                                    "select m from EstimateLineRoomMaterialEntity m "
                                            + "where m.branch = :branch "
                                            + "and m.constructionType.id = :typeId "
                                            + "and m.roomQty.line.workItem.id = :workItemId",
                                    EstimateLineRoomMaterialEntity.class)
                            .setParameter("branch", ConsumptionBranch.construction)
                            .setParameter("typeId", f.constructionTypeId)
                            .setParameter("workItemId", f.workItemId)
                            .getResultList();

            assertThat(constructionLines)
                    .as("both assigned cells must have a construction line of the type").hasSize(2);
            assertThat(constructionLines)
                    .allSatisfy(line -> {
                        assertThat(line.getConcreteConstructionMaterial())
                                .as("bulk choose must fill every assigned cell (R9.3)").isNotNull();
                        assertThat(line.getConcreteConstructionMaterial().getId())
                                .isEqualTo(f.constructionMaterialId);
                        assertThat(line.getConcreteNet()).isEqualByComparingTo(f.constructionRetailNet);
                    });
        });
    }

    @Test
    @DisplayName("unassign removes the room-qty and cascade-deletes its material collection and the "
            + "now-empty line (R19.3)")
    void cascadeDeleteOnUnassign() {
        Fixture f = createFixture();
        EstimateLineRoomQtyEntity roomQty = assignmentService.assign(f.projectId, f.workItemId, f.roomId, null);
        Long roomQtyId = roomQty.getId();

        // Capture the seeded material line ids so we can assert they are gone after cascade.
        List<Long> materialIds = tx().execute(status ->
                estimateLineRoomQtyDao.findById(roomQtyId).orElseThrow().getMaterials().stream()
                        .map(EstimateLineRoomMaterialEntity::getId)
                        .toList());
        assertThat(materialIds).as("material lines must have been seeded before unassign").isNotEmpty();

        assignmentService.unassign(f.projectId, f.workItemId, f.roomId);

        tx().executeWithoutResult(status -> {
            assertThat(estimateLineRoomQtyDao.findById(roomQtyId))
                    .as("the room-qty must be deleted on unassign").isEmpty();
            for (Long materialId : materialIds) {
                assertThat(estimateLineRoomMaterialDao.findById(materialId))
                        .as("each material line must cascade-delete with its room-qty (R19.3)").isEmpty();
            }
            Long remainingLines = entityManager.createQuery(
                            "select count(l) from EstimateLineEntity l "
                                    + "where l.workItem.id = :workItemId and l.estimate.project.id = :projectId",
                            Long.class)
                    .setParameter("workItemId", f.workItemId)
                    .setParameter("projectId", f.projectId)
                    .getSingleResult();
            assertThat(remainingLines)
                    .as("the now-empty line must be dropped once it has no rooms left (R19.3)")
                    .isZero();
        });
    }

    // =====================================================================================
    // Fixture building
    // =====================================================================================

    /** The ids a test needs, captured after the fixture graph is committed. */
    private static final class Fixture {
        Long projectId;
        Long roomId;
        Long roomTypeId;
        Long workItemId;
        Long constructionTypeId;
        Long constructionMaterialId;
        BigDecimal roomFloorArea;
        BigDecimal workNetPrice;
        BigDecimal constructionRetailNet;
    }

    /**
     * Builds a self-contained estimate graph in one transaction: a DRAFT project + estimate, a room
     * (with a known floorArea), an {@code m2}-unit work item with a construction + finishing
     * consumption (so assign seeds two material lines), a WorkPrice, and one active priced material
     * per branch (so the frozen ranges are non-null).
     */
    private Fixture createFixture() {
        Fixture f = new Fixture();
        tx().executeWithoutResult(status -> {
            CurrencyEntity currency = persistCurrency();
            MeasurementUnitEntity m2Unit = persistMeasurementUnit("m2");

            WorkCategoryEntity category = persistWorkCategory();
            WorkItemEntity workItem = persistWorkItem("W_" + runId, category, m2Unit);

            BigDecimal netPrice = new BigDecimal("150.00");
            persistWorkPrice(workItem, currency, netPrice);

            // Construction consumption + a priced active material of the type.
            ConstructionMaterialTypeEntity constructionType = persistConstructionType("CT_" + runId);
            BigDecimal constructionRetailNet = new BigDecimal("42.00");
            ConstructionMaterialEntity constructionMaterial =
                    persistConstructionMaterial(constructionType, m2Unit, currency, constructionRetailNet);
            persistConstructionConsumption(workItem, constructionType, m2Unit);

            // Finishing consumption + a priced active finishing material of the type.
            MaterialTypeEntity finishingType = persistMaterialType("FT_" + runId);
            persistFinishingMaterial(finishingType, m2Unit, new BigDecimal("77.00"));
            persistFinishingConsumption(workItem, finishingType, m2Unit);

            ProjectEntity project = persistProject();
            RoomTypeEntity roomType = persistRoomType();
            BigDecimal floorArea = new BigDecimal("20.00");
            RoomEntity room = persistRoom(project, roomType, floorArea);

            // Create the DRAFT estimate for the project (get-or-create).
            estimateService.getOrCreateForProject(project.getId());

            f.projectId = project.getId();
            f.roomId = room.getId();
            f.roomTypeId = roomType.getId();
            f.workItemId = workItem.getId();
            f.constructionTypeId = constructionType.getId();
            f.constructionMaterialId = constructionMaterial.getId();
            f.roomFloorArea = floorArea;
            f.workNetPrice = netPrice;
            f.constructionRetailNet = constructionRetailNet;
        });
        return f;
    }

    private boolean hasConstructionLine(List<EstimateLineRoomMaterialEntity> materials, Long typeId) {
        return materials.stream().anyMatch(m -> m.getBranch() == ConsumptionBranch.construction
                && m.getConstructionType() != null && typeId.equals(m.getConstructionType().getId()));
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

    /**
     * Get-or-creates a measurement unit by its <b>exact</b> code (e.g. {@code m2}). The code is NOT
     * suffixed with the run id because {@code VolumeFallbackResolver} maps by the exact (normalized)
     * unit code — the work must carry the literal {@code m2} unit for the floorArea fallback to
     * resolve. It is a get-or-create so it survives across the class's tests (like the PLN currency)
     * and is intentionally left out of {@link #cleanUp()} for the same reason.
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
        consumption.setSourceType("MANUAL");
        consumption.setSourceDoc("test");
        consumption.setSourceRef("test");
        workMaterialConsumptionDao.save(consumption);
    }

    private MaterialTypeEntity persistMaterialType(String code) {
        MaterialTypeEntity type = new MaterialTypeEntity();
        type.setCode(code);
        type.setNameRU("Тип " + runId);
        type.setNamePL("Typ " + runId);
        type.setActive(true);
        return materialTypeDao.save(type);
    }

    private void persistFinishingMaterial(MaterialTypeEntity type, MeasurementUnitEntity unit, BigDecimal retailNet) {
        MaterialCategoryEntity category = new MaterialCategoryEntity();
        category.setCode("MCAT_" + runId);
        category.setNameRU("Категория " + runId);
        category.setNamePL("Kategoria " + runId);
        category.setActive(true);
        materialCategoryDao.save(category);

        MaterialEntity material = new MaterialEntity();
        material.setCode("MAT_" + runId);
        material.setNameRU("Материал " + runId);
        material.setNamePL("Materiał " + runId);
        material.setActive(true);
        materialDao.save(material);

        FinishingMaterialEntity finishing = new FinishingMaterialEntity();
        finishing.setCategory(category);
        finishing.setMaterial(material);
        finishing.setType(type);
        finishing.setUnit(unit);
        finishing.setRetailNet(retailNet);
        finishing.setActive(true);
        finishingMaterialDao.save(finishing);
    }

    private void persistFinishingConsumption(
            WorkItemEntity workItem, MaterialTypeEntity type, MeasurementUnitEntity materialUnit) {
        WorkMaterialConsumptionEntity consumption = new WorkMaterialConsumptionEntity();
        consumption.setWorkItem(workItem);
        consumption.setMaterialUnit(materialUnit);
        consumption.setBranch(ConsumptionBranch.finishing);
        consumption.setFinishingMaterialType(type);
        consumption.setNormQty(new BigDecimal("2.0000"));
        consumption.setSourceType("MANUAL");
        consumption.setSourceDoc("test");
        consumption.setSourceRef("test");
        workMaterialConsumptionDao.save(consumption);
    }

    private ProjectEntity persistProject() {
        ProjectEntity project = new ProjectEntity();
        project.setName("Assign IT Project " + runId + "-" + COUNTER.incrementAndGet());
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
