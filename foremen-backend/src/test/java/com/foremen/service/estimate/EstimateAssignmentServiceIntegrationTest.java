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

import com.foremen.dao.AssortmentGroupDao;
import com.foremen.dao.AssortmentPositionDao;
import com.foremen.dao.AssortmentPositionPriceDao;
import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.CurrencyDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.EstimateLineRoomQtyDao;
import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MaterialCategoryDao;
import com.foremen.dao.MaterialDao;
import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.WorkPackageOverrideDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.AssortmentGroupEntity;
import com.foremen.dao.model.AssortmentPositionEntity;
import com.foremen.dao.model.AssortmentPositionPriceEntity;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.dao.model.WorkPackageOverrideEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.EstimateService;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;

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
    private OfferPackageDao offerPackageDao;
    @Autowired
    private AssortmentGroupDao assortmentGroupDao;
    @Autowired
    private AssortmentPositionDao assortmentPositionDao;
    @Autowired
    private AssortmentPositionPriceDao assortmentPositionPriceDao;
    @Autowired
    private WorkPackageOverrideDao workPackageOverrideDao;

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
            // FOR-05-05 Amendment A1 / Wave 1b fixtures: MUST be deleted BEFORE room_types (the
            // group->room-type join FK). The per-package position->work link
            // (assortment_position_work_items) is ON DELETE CASCADE, so deleting positions/packages
            // clears it. Order: prices -> group room-type join -> positions -> groups; overrides ->
            // packages.
            entityManager.createQuery(
                            "delete from AssortmentPositionPriceEntity pr where pr.offerPackage.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createNativeQuery(
                            "delete from assortment_group_room_types where assortment_group_id in "
                                    + "(select id from assortment_groups where name_ru like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from AssortmentPositionEntity ap where ap.group.id in "
                                    + "(select g.id from AssortmentGroupEntity g where g.nameRU like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from AssortmentGroupEntity g where g.nameRU like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from WorkPackageOverrideEntity o where o.offerPackage.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from OfferPackageEntity op where op.code like :p")
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

    // =====================================================================================
    // Staged-batch material-edit replay (saveAndAssemble / previewStagedEdits)
    //
    // FOR-05-05 fix: staged material edits are resolved by the natural keys the client always has
    // — (workItemId, roomId, branch, typeId) — walked over the in-memory estimate graph, NOT by a
    // persisted roomQtyId/materialLineId. This makes edits replay correctly against a cell assigned
    // EARLIER IN THE SAME BATCH (no persisted id yet) and makes the preview total (no throw / no 404
    // when the target cell/line is absent).
    // =====================================================================================

    /** Full-arity staged-edit factory (the record has no convenience factory for material edits). */
    private static EstimateAssignmentService.StagedEdit addMaterial(
            Long workItemId, Long roomId, ConsumptionBranch branch, Long typeId) {
        return new EstimateAssignmentService.StagedEdit(
                EstimateAssignmentService.EditKind.ADD_MATERIAL,
                workItemId, roomId, null, branch, typeId, null, null, null, null);
    }

    private static EstimateAssignmentService.StagedEdit removeMaterial(
            Long workItemId, Long roomId, ConsumptionBranch branch, Long typeId) {
        return new EstimateAssignmentService.StagedEdit(
                EstimateAssignmentService.EditKind.REMOVE_MATERIAL,
                workItemId, roomId, null, branch, typeId, null, null, null, null);
    }

    private static EstimateAssignmentService.StagedEdit chooseConcrete(
            Long workItemId, Long roomId, ConsumptionBranch branch, Long typeId, Long materialId) {
        return new EstimateAssignmentService.StagedEdit(
                EstimateAssignmentService.EditKind.CHOOSE_CONCRETE,
                workItemId, roomId, null, branch, typeId, null, materialId, null, null);
    }

    /** Full-arity {@code SET_QUANTITY} staged edit: override the (work, room) cell's Volume (#7). */
    private static EstimateAssignmentService.StagedEdit setQuantity(
            Long workItemId, Long roomId, BigDecimal quantity) {
        return new EstimateAssignmentService.StagedEdit(
                EstimateAssignmentService.EditKind.SET_QUANTITY,
                workItemId, roomId, null, null, null, null, null, null, quantity);
    }

    /** Full-arity {@code CLEAR_QUANTITY} staged edit: revert the (work, room) cell to the formula (#7). */
    private static EstimateAssignmentService.StagedEdit clearQuantity(Long workItemId, Long roomId) {
        return new EstimateAssignmentService.StagedEdit(
                EstimateAssignmentService.EditKind.CLEAR_QUANTITY,
                workItemId, roomId, null, null, null, null, null, null, null);
    }

    /**
     * The COARSE work-level {@code APPLY_PACKAGE} the work-row hammer / Apply_Package button stages:
     * it carries only {@code (workItemId, packageCode)} and a {@code null} roomId — meaning "apply
     * this work to all its matching rooms". The backend must expand it via
     * {@code calculateApplyWorkToRooms} rather than treat it as a single per-cell assign (the
     * FOR-05-05 404 bug was {@code resolveRoom(null)} on this shape).
     */
    private static EstimateAssignmentService.StagedEdit coarseApplyPackage(Long workItemId, String packageCode) {
        return new EstimateAssignmentService.StagedEdit(
                EstimateAssignmentService.EditKind.APPLY_PACKAGE,
                workItemId, null, null, null, null, null, null, packageCode, null);
    }

    @Test
    @DisplayName("staged batch: ADD_MATERIAL targeting a cell assigned EARLIER IN THE SAME BATCH "
            + "(no persisted id) adds the (branch, type) line — resolved by natural keys (R6.2)")
    void stagedBatchAddMaterialOnSameBatchCell() {
        Fixture f = createFixture();

        // A construction type NOT part of the work's consumption -> not seeded on assign.
        Long extraTypeId = tx().execute(status -> persistConstructionType("EXTRA_" + runId).getId());

        // Staged set: ASSIGN (creates the cell in-memory, no persisted id yet) THEN ADD_MATERIAL on it.
        List<EstimateAssignmentService.StagedEdit> edits = List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null),
                addMaterial(f.workItemId, f.roomId, ConsumptionBranch.construction, extraTypeId));

        assignmentService.saveAndAssemble(f.projectId, edits);

        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity rq = loadRoomQty(f.projectId, f.workItemId, f.roomId);
            assertThat(hasConstructionLine(rq.getMaterials(), extraTypeId))
                    .as("ADD_MATERIAL resolved by (workItemId, roomId, branch, typeId) must add the line "
                            + "on the same-batch cell (R6.2)").isTrue();
        });
    }

    @Test
    @DisplayName("staged batch: single-cell REMOVE_MATERIAL (roomId set) removes the (branch, type) "
            + "line resolved by natural keys (R6.2)")
    void stagedBatchRemoveMaterialSingleCell() {
        Fixture f = createFixture();
        Long extraTypeId = tx().execute(status -> persistConstructionType("EXTRA_" + runId).getId());

        // ASSIGN + ADD then, in a second batch, REMOVE the same line by natural keys.
        assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null),
                addMaterial(f.workItemId, f.roomId, ConsumptionBranch.construction, extraTypeId)));

        assignmentService.saveAndAssemble(f.projectId, List.of(
                removeMaterial(f.workItemId, f.roomId, ConsumptionBranch.construction, extraTypeId)));

        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity rq = loadRoomQty(f.projectId, f.workItemId, f.roomId);
            assertThat(hasConstructionLine(rq.getMaterials(), extraTypeId))
                    .as("single-cell REMOVE_MATERIAL must remove the (branch, type) line (R6.2)").isFalse();
        });
    }

    @Test
    @DisplayName("staged batch: bulk REMOVE_MATERIAL (roomId null) removes the (branch, type) line "
            + "across every assigned cell of the work (R6.2)")
    void stagedBatchRemoveMaterialBulk() {
        Fixture f = createFixture();
        Long roomId2 = tx().execute(status -> persistRoom(
                projectDao.findById(f.projectId).orElseThrow(),
                roomTypeDao.findById(f.roomTypeId).orElseThrow(),
                new BigDecimal("30.00")).getId());

        // Assign the work to two rooms — the seeded construction consumption line exists on both.
        assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null),
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, roomId2, null)));

        // Bulk remove: roomId null -> remove the seeded (construction, type) line across all cells.
        assignmentService.saveAndAssemble(f.projectId, List.of(
                removeMaterial(f.workItemId, null, ConsumptionBranch.construction, f.constructionTypeId)));

        tx().executeWithoutResult(status -> {
            Long remaining = entityManager.createQuery(
                            "select count(m) from EstimateLineRoomMaterialEntity m "
                                    + "where m.branch = :branch and m.constructionType.id = :typeId "
                                    + "and m.roomQty.line.workItem.id = :workItemId "
                                    + "and m.roomQty.line.estimate.project.id = :projectId",
                            Long.class)
                    .setParameter("branch", ConsumptionBranch.construction)
                    .setParameter("typeId", f.constructionTypeId)
                    .setParameter("workItemId", f.workItemId)
                    .setParameter("projectId", f.projectId)
                    .getSingleResult();
            assertThat(remaining)
                    .as("bulk REMOVE_MATERIAL (roomId null) must remove the line from every assigned cell (R6.2)")
                    .isZero();
        });
    }

    @Test
    @DisplayName("staged batch: CHOOSE_CONCRETE resolved by (branch, typeId) within the cell (NOT by "
            + "materialLineId) sets the product and collapses the line (R6.3, R6.4, R13.4)")
    void stagedBatchChooseConcreteByBranchType() {
        Fixture f = createFixture();

        // Single batch: ASSIGN (seeds the construction line, no persisted id) THEN CHOOSE_CONCRETE
        // on that line addressed by (branch, typeId) — the client never has a materialLineId here.
        assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null),
                chooseConcrete(f.workItemId, f.roomId, ConsumptionBranch.construction,
                        f.constructionTypeId, f.constructionMaterialId)));

        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity rq = loadRoomQty(f.projectId, f.workItemId, f.roomId);
            EstimateLineRoomMaterialEntity line = rq.getMaterials().stream()
                    .filter(m -> m.getBranch() == ConsumptionBranch.construction
                            && m.getConstructionType() != null
                            && f.constructionTypeId.equals(m.getConstructionType().getId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the seeded construction line must exist"));
            assertThat(line.getConcreteConstructionMaterial())
                    .as("CHOOSE_CONCRETE resolved by (branch, typeId) must set the product (R6.3)").isNotNull();
            assertThat(line.getConcreteConstructionMaterial().getId()).isEqualTo(f.constructionMaterialId);
            assertThat(line.getConcreteNet())
                    .as("choosing the concrete product collapses the line to its retailNet (R6.4)")
                    .isEqualByComparingTo(f.constructionRetailNet);
        });
    }

    @Test
    @DisplayName("staged preview: material edits on a NOT-assigned cell are a total no-op — the "
            + "preview returns a matrix instead of throwing/404 (R15.6)")
    void stagedPreviewMaterialEditOnAbsentCellIsNoOp() {
        Fixture f = createFixture();
        Long extraTypeId = tx().execute(status -> persistConstructionType("EXTRA_" + runId).getId());

        // No ASSIGN in the batch -> the target cell is absent from the staged graph. Every material
        // edit must be a no-op and the preview must be total (this is the FOR-05-05 404 bug).
        List<EstimateAssignmentService.StagedEdit> edits = List.of(
                addMaterial(f.workItemId, f.roomId, ConsumptionBranch.construction, extraTypeId),
                removeMaterial(f.workItemId, f.roomId, ConsumptionBranch.construction, extraTypeId),
                chooseConcrete(f.workItemId, f.roomId, ConsumptionBranch.construction,
                        f.constructionTypeId, f.constructionMaterialId));

        EstimateMatrixDto preview = assignmentService.previewStagedEdits(f.projectId, edits, true);

        assertThat(preview)
                .as("preview of material edits on an unassigned cell must return a matrix, not 404 (R15.6)")
                .isNotNull();

        // And it persisted nothing: no line exists for the work.
        tx().executeWithoutResult(status -> {
            Long lines = entityManager.createQuery(
                            "select count(l) from EstimateLineEntity l "
                                    + "where l.workItem.id = :workItemId and l.estimate.project.id = :projectId",
                            Long.class)
                    .setParameter("workItemId", f.workItemId)
                    .setParameter("projectId", f.projectId)
                    .getSingleResult();
            assertThat(lines).as("preview must persist nothing (R15.6)").isZero();
        });
    }

    @Test
    @DisplayName("staged preview: a full batch [ASSIGN, ADD_MATERIAL, CHOOSE_CONCRETE] on a same-batch "
            + "cell previews without throwing and persists nothing (R15.6)")
    void stagedPreviewSameBatchMaterialEditPersistsNothing() {
        Fixture f = createFixture();
        Long extraTypeId = tx().execute(status -> persistConstructionType("EXTRA_" + runId).getId());

        List<EstimateAssignmentService.StagedEdit> edits = List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null),
                addMaterial(f.workItemId, f.roomId, ConsumptionBranch.construction, extraTypeId),
                chooseConcrete(f.workItemId, f.roomId, ConsumptionBranch.construction,
                        f.constructionTypeId, f.constructionMaterialId));

        EstimateMatrixDto preview = assignmentService.previewStagedEdits(f.projectId, edits, true);
        assertThat(preview)
                .as("a same-batch staged material edit must preview successfully, not 404 (R15.6)")
                .isNotNull();

        tx().executeWithoutResult(status -> {
            Long lines = entityManager.createQuery(
                            "select count(l) from EstimateLineEntity l "
                                    + "where l.workItem.id = :workItemId and l.estimate.project.id = :projectId",
                            Long.class)
                    .setParameter("workItemId", f.workItemId)
                    .setParameter("projectId", f.projectId)
                    .getSingleResult();
            assertThat(lines).as("preview must roll back and persist nothing (R15.6)").isZero();
        });
    }

    @Test
    @DisplayName("staged batch: a COARSE work-level APPLY_PACKAGE (roomId null) expands via "
            + "calculateApplyWorkToRooms and assigns the work's matching room(s) — the FOR-05-05 "
            + "hammer Save no longer 404s (R9.5, R15.3)")
    void stagedCoarseApplyPackageAssignsMatchingRooms() {
        Fixture f = createFixture();

        // Staged set = the single coarse work-level APPLY_PACKAGE the hammer stages (no roomId).
        // The fixture work has an EMPTY Room_Type_Attachment => attaches to all rooms, and the
        // project has exactly one room, so the coarse edit must expand to one per-cell assign.
        assignmentService.saveAndAssemble(f.projectId, List.of(coarseApplyPackage(f.workItemId, null)));

        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity rq = loadRoomQty(f.projectId, f.workItemId, f.roomId);
            assertThat(rq)
                    .as("the coarse APPLY_PACKAGE must have assigned the work's matching room (R9.5)")
                    .isNotNull();
            assertThat(rq.getQuantity())
                    .as("the expanded per-cell assign must resolve a non-null Volume (m2 floorArea fallback)")
                    .isNotNull();
            assertThat(rq.getQuantity())
                    .as("the resolved Volume is the room's floorArea via the m2 fallback")
                    .isEqualByComparingTo(f.roomFloorArea);
            assertThat(rq.getMaterials())
                    .as("the expanded assign must seed the frozen material line per consumption type (R3.4)")
                    .hasSize(2);
            assertThat(rq.getLine().getUnitPrice())
                    .as("the expanded assign must copy the frozen labour price (R3.4/R13.1)")
                    .isEqualByComparingTo(f.workNetPrice);
        });
    }

    @Test
    @DisplayName("staged preview: a batch [coarse APPLY_PACKAGE (roomId null), single-cell "
            + "REMOVE_MATERIAL] previews without 404 — the coarse edit expands and the removed "
            + "(branch, type) line is gone — and nothing persists (R9.5, R15.6)")
    void stagedCoarseApplyPackageThenMaterialEditPreview() {
        Fixture f = createFixture();

        // Coarse APPLY_PACKAGE (expands to assign the fixture room) THEN remove the seeded finishing
        // material line of that same-batch cell, addressed by natural keys (branch, typeId).
        List<EstimateAssignmentService.StagedEdit> edits = List.of(
                coarseApplyPackage(f.workItemId, null),
                removeMaterial(f.workItemId, f.roomId, ConsumptionBranch.construction, f.constructionTypeId));

        EstimateMatrixDto preview = assignmentService.previewStagedEdits(f.projectId, edits, true);
        assertThat(preview)
                .as("a coarse APPLY_PACKAGE + material edit must preview successfully, not 404 (R9.5/R15.6)")
                .isNotNull();

        // The preview must roll back: no line is persisted for the work (R15.6).
        tx().executeWithoutResult(status -> {
            Long lines = entityManager.createQuery(
                            "select count(l) from EstimateLineEntity l "
                                    + "where l.workItem.id = :workItemId and l.estimate.project.id = :projectId",
                            Long.class)
                    .setParameter("workItemId", f.workItemId)
                    .setParameter("projectId", f.projectId)
                    .getSingleResult();
            assertThat(lines).as("the coarse-apply preview must persist nothing (R15.6)").isZero();
        });

        // A follow-up matrix read shows no assignment — confirming the preview rolled back (R15.6).
        EstimateMatrixDto afterRead = assignmentService.getMatrix(f.projectId, true);
        boolean anyAssignedCell = afterRead.groups().stream()
                .flatMap(group -> group.rows().stream())
                .flatMap(row -> row.cells().stream())
                .anyMatch(cell -> cell != null && cell.assigned());
        assertThat(anyAssignedCell)
                .as("after the rolled-back preview the estimate must still have no assigned cells (R15.6)")
                .isFalse();
    }

    /** Loads the (workItemId, roomId) room-qty for the project's estimate, failing if absent. */
    private EstimateLineRoomQtyEntity loadRoomQty(Long projectId, Long workItemId, Long roomId) {
        return entityManager.createQuery(
                        "select rq from EstimateLineRoomQtyEntity rq "
                                + "where rq.line.workItem.id = :workItemId and rq.room.id = :roomId "
                                + "and rq.line.estimate.project.id = :projectId",
                        EstimateLineRoomQtyEntity.class)
                .setParameter("workItemId", workItemId)
                .setParameter("roomId", roomId)
                .setParameter("projectId", projectId)
                .getSingleResult();
    }

    // =====================================================================================
    // Quantity / Volume override (#7): SET_QUANTITY overrides the formula Volume for a cell,
    // survives Save, is protected on reassign; CLEAR_QUANTITY reverts to the formula Volume.
    //
    // Validates: Requirements 4.1 (one Volume per cell), 15.2/15.7 (batched Save persistence),
    // 15.6 (preview persists nothing).
    // =====================================================================================

    @Test
    @DisplayName("staged #7: SET_QUANTITY overrides the cell Volume, survives Save (persisted + "
            + "reflected in the assembled matrix cell), and flags volumeOverridden")
    void stagedSetQuantityOverridesVolumeAndSurvivesSave() {
        Fixture f = createFixture();
        BigDecimal override = new BigDecimal("99.00");

        // Batch: ASSIGN (in-memory cell, formula Volume = floorArea) THEN SET_QUANTITY on it.
        EstimateMatrixDto matrix = assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null),
                setQuantity(f.workItemId, f.roomId, override)));

        // Persisted: the stored quantity is the manual override, and the cell is flagged overridden.
        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity rq = loadRoomQty(f.projectId, f.workItemId, f.roomId);
            assertThat(rq.getQuantity())
                    .as("SET_QUANTITY must persist the manual Volume override (#7)")
                    .isEqualByComparingTo(override);
            assertThat(rq.isVolumeOverridden())
                    .as("SET_QUANTITY must flag the cell as a manual Volume override (#7)").isTrue();
        });

        // Reflected in the assembled matrix cell returned by Save.
        var cell = findCell(matrix, f.workItemId, f.roomId);
        assertThat(cell).as("the assembled matrix must contain the (work, room) cell").isNotNull();
        assertThat(cell.volume())
                .as("the assembled cell Volume must be the manual override (#7)")
                .isEqualByComparingTo(override);
        assertThat(cell.volumeOverridden())
                .as("the assembled cell must expose volumeOverridden = true (#7)").isTrue();
    }

    @Test
    @DisplayName("staged #7: a later ASSIGN (which would normally re-derive the Volume) does NOT "
            + "overwrite an overridden cell's manual Volume")
    void stagedReassignDoesNotOverwriteOverriddenVolume() {
        Fixture f = createFixture();
        BigDecimal override = new BigDecimal("99.00");

        // First Save: assign + override.
        assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null),
                setQuantity(f.workItemId, f.roomId, override)));

        // Second Save: re-assign the same cell — normally re-derives the formula Volume.
        assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null)));

        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity rq = loadRoomQty(f.projectId, f.workItemId, f.roomId);
            assertThat(rq.getQuantity())
                    .as("reassign must NOT overwrite an overridden cell's manual Volume (#7)")
                    .isEqualByComparingTo(override);
            assertThat(rq.isVolumeOverridden())
                    .as("the override flag must remain set after a protecting reassign (#7)").isTrue();
        });
    }

    @Test
    @DisplayName("staged #7: CLEAR_QUANTITY clears the override and reverts the cell to the "
            + "formula-resolved Volume (the fixture's floorArea fallback)")
    void stagedClearQuantityRevertsToFormula() {
        Fixture f = createFixture();
        BigDecimal override = new BigDecimal("99.00");

        // Assign + override.
        assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null),
                setQuantity(f.workItemId, f.roomId, override)));

        // Clear the override -> re-derive the formula Volume (m2 floorArea fallback).
        assignmentService.saveAndAssemble(f.projectId, List.of(
                clearQuantity(f.workItemId, f.roomId)));

        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity rq = loadRoomQty(f.projectId, f.workItemId, f.roomId);
            assertThat(rq.isVolumeOverridden())
                    .as("CLEAR_QUANTITY must clear the override flag (#7)").isFalse();
            assertThat(rq.getQuantity())
                    .as("CLEAR_QUANTITY must revert the Volume to the formula (room floorArea fallback) (#7)")
                    .isEqualByComparingTo(f.roomFloorArea);
        });
    }

    @Test
    @DisplayName("staged #7: SET_QUANTITY with a non-positive value is a total no-op — the preview "
            + "returns a matrix and nothing is persisted (#7, R15.6)")
    void stagedSetQuantityNonPositiveIsNoOp() {
        Fixture f = createFixture();

        // Preview a batch that assigns then sets a non-positive (0) quantity: the SET must be a no-op.
        List<EstimateAssignmentService.StagedEdit> edits = List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null),
                setQuantity(f.workItemId, f.roomId, BigDecimal.ZERO));

        EstimateMatrixDto preview = assignmentService.previewStagedEdits(f.projectId, edits, true);
        assertThat(preview)
                .as("SET_QUANTITY=0 must not throw — the preview returns a matrix (#7, R15.6)").isNotNull();

        // The non-positive SET is a no-op: the assembled cell keeps the formula Volume, not overridden.
        var cell = findCell(preview, f.workItemId, f.roomId);
        assertThat(cell).as("the previewed (work, room) cell must exist").isNotNull();
        assertThat(cell.volumeOverridden())
                .as("a non-positive SET_QUANTITY must NOT flag the cell overridden (#7)").isFalse();
        assertThat(cell.volume())
                .as("a non-positive SET_QUANTITY leaves the formula Volume (floorArea fallback) (#7)")
                .isEqualByComparingTo(f.roomFloorArea);

        // Preview persists nothing.
        assertNoLinePersisted(f.projectId, f.workItemId);
    }

    @Test
    @DisplayName("staged #7: SET_QUANTITY on an unassigned cell is a total no-op — the preview "
            + "returns a matrix and nothing is persisted (#7, R15.6)")
    void stagedSetQuantityOnAbsentCellIsNoOp() {
        Fixture f = createFixture();

        // No ASSIGN in the batch -> the target cell is absent from the staged graph.
        List<EstimateAssignmentService.StagedEdit> edits = List.of(
                setQuantity(f.workItemId, f.roomId, new BigDecimal("99.00")));

        EstimateMatrixDto preview = assignmentService.previewStagedEdits(f.projectId, edits, true);
        assertThat(preview)
                .as("SET_QUANTITY on an absent cell must not throw — the preview returns a matrix (#7, R15.6)")
                .isNotNull();

        // Nothing was assigned or persisted.
        var cell = findCell(preview, f.workItemId, f.roomId);
        assertThat(cell == null || !cell.assigned())
                .as("SET_QUANTITY on an unassigned cell must not create an assignment (#7)").isTrue();
        assertNoLinePersisted(f.projectId, f.workItemId);
    }

    /** Finds the (workItemId, roomId) cell in an assembled matrix, or {@code null} when absent. */
    private com.foremen.service.estimate.matrix.CellDto findCell(
            EstimateMatrixDto matrix, Long workItemId, Long roomId) {
        return matrix.groups().stream()
                .flatMap(group -> group.rows().stream())
                .flatMap(row -> row.cells().stream())
                .filter(cell -> cell != null
                        && workItemId.equals(cell.workItemId()) && roomId.equals(cell.roomId()))
                .findFirst()
                .orElse(null);
    }

    /** Asserts the project's estimate has no persisted line for {@code workItemId} (preview rollback). */
    private void assertNoLinePersisted(Long projectId, Long workItemId) {
        tx().executeWithoutResult(status -> {
            Long lines = entityManager.createQuery(
                            "select count(l) from EstimateLineEntity l "
                                    + "where l.workItem.id = :workItemId and l.estimate.project.id = :projectId",
                            Long.class)
                    .setParameter("workItemId", workItemId)
                    .setParameter("projectId", projectId)
                    .getSingleResult();
            assertThat(lines).as("preview must persist nothing (R15.6)").isZero();
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

    @Test
    @DisplayName("getMatrix on a project with no estimate get-or-creates a defaulted DRAFT estimate "
            + "and returns an empty, assignable matrix rather than 404 (R1.7)")
    void getMatrixCreatesEstimateWhenAbsent() {
        // A project + rooms fixture WITHOUT an estimate row (an existing project created before the
        // estimate feature).
        Fixture f = createFixture(false);

        // Sanity: no estimate exists yet.
        tx().executeWithoutResult(status ->
                assertThat(estimateDao.findByProjectId(f.projectId))
                        .as("the project must have no estimate before the first matrix read (R1.7)")
                        .isEmpty());

        // First matrix read must NOT 404 — it get-or-creates the estimate and returns a matrix.
        EstimateMatrixDto matrix = assignmentService.getMatrix(f.projectId, true);

        assertThat(matrix)
                .as("the first matrix read must return a non-null matrix, not fail (R1.7)").isNotNull();
        assertThat(matrix.projectId()).isEqualTo(f.projectId);
        assertThat(matrix.rooms())
                .as("the matrix must render the project's room columns (R1.1)").isNotEmpty();
        assertThat(matrix.groups())
                .as("the matrix must render work-type groups (R2.1)").isNotNull();
        // Empty matrix: no cell is assigned on a brand-new estimate (R1.3, design "empty matrix").
        boolean anyAssignedCell = matrix.groups().stream()
                .flatMap(group -> group.rows().stream())
                .flatMap(row -> row.cells().stream())
                .anyMatch(cell -> cell != null && cell.assigned());
        assertThat(anyAssignedCell)
                .as("a fresh estimate has no assigned cells — the matrix is empty/assignable (R1.3)")
                .isFalse();

        // Exactly one estimate now exists, defaulted to DRAFT (R1.7).
        Long createdEstimateId = tx().execute(status -> {
            EstimateEntity estimate = estimateDao.findByProjectId(f.projectId)
                    .orElseThrow(() -> new AssertionError("the matrix read must have created the estimate (R1.7)"));
            assertThat(estimate.getStatus())
                    .as("the created estimate must default to DRAFT (R1.7)")
                    .isEqualTo(com.foremen.dao.model.EstimateStatus.DRAFT);
            return estimate.getId();
        });

        // A second matrix read resolves the SAME estimate — no duplicate created (R1.7).
        EstimateMatrixDto secondRead = assignmentService.getMatrix(f.projectId, true);
        assertThat(secondRead).as("a repeated matrix read must also succeed").isNotNull();

        tx().executeWithoutResult(status -> {
            Long estimateCount = entityManager.createQuery(
                            "select count(e) from EstimateEntity e where e.project.id = :projectId", Long.class)
                    .setParameter("projectId", f.projectId)
                    .getSingleResult();
            assertThat(estimateCount)
                    .as("repeated reads must resolve the same estimate, never a second one (R1.7)")
                    .isEqualTo(1L);
            assertThat(estimateDao.findByProjectId(f.projectId).orElseThrow().getId())
                    .as("the second read must resolve the same estimate id (R1.7)")
                    .isEqualTo(createdEstimateId);
        });
    }

    // =====================================================================================
    // Apply cheapest products (#19): APPLY_CHEAPEST fills every in-scope PLACEHOLDER material line
    // with the CHEAPEST concrete product of its type, collapsing the line to that retailNet. Scope
    // is optional: whole matrix (header), one work (row), or one cell.
    //
    // Validates: Requirements 6.3/6.4 (choose concrete collapses to the product retailNet),
    // 6.6 (placeholder), 15.6 (preview must be total / non-throwing).
    // =====================================================================================

    @Test
    @DisplayName("staged #19: APPLY_CHEAPEST scoped to a cell fills each construction PLACEHOLDER "
            + "line with the MIN-retailNet product of its type and collapses concreteNet to that min")
    void stagedApplyCheapestChoosesMinPriceConcretePerLine() {
        Fixture f = createFixture();

        // Add a SECOND, cheaper priced construction product of the SAME seeded type (fixture's is
        // 42.00) so the type has >=2 priced products at different retailNet; 19.00 is the cheapest.
        BigDecimal cheaperNet = new BigDecimal("19.00");
        tx().executeWithoutResult(status -> {
            CurrencyEntity currency = persistCurrency();
            MeasurementUnitEntity m2Unit = persistMeasurementUnit("m2");
            ConstructionMaterialTypeEntity type =
                    constructionMaterialTypeDao.findById(f.constructionTypeId).orElseThrow();
            persistConstructionMaterial(type, m2Unit, currency, cheaperNet);
        });

        // ASSIGN (seeds the construction placeholder line) THEN APPLY_CHEAPEST scoped to that cell.
        assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null),
                EstimateAssignmentService.StagedEdit.applyCheapest(f.workItemId, f.roomId)));

        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity rq = loadRoomQty(f.projectId, f.workItemId, f.roomId);
            EstimateLineRoomMaterialEntity constructionLine = rq.getMaterials().stream()
                    .filter(m -> m.getBranch() == ConsumptionBranch.construction)
                    .findFirst().orElseThrow();
            assertThat(constructionLine.getConcreteConstructionMaterial())
                    .as("APPLY_CHEAPEST must fill the placeholder with a concrete product (#19)").isNotNull();
            assertThat(constructionLine.getConcreteNet())
                    .as("APPLY_CHEAPEST must collapse the line to the MIN retailNet of the type (#19)")
                    .isEqualByComparingTo(cheaperNet);
        });
    }

    @Test
    @DisplayName("staged #19: APPLY_CHEAPEST scoped to a work (roomId null) fills the placeholders "
            + "across ALL of the work's assigned cells")
    void stagedApplyCheapestWorkScopeFillsAllCells() {
        Fixture f = createFixture();
        Long roomId2 = tx().execute(status -> {
            ProjectEntity project = projectDao.findById(f.projectId).orElseThrow();
            RoomTypeEntity roomType = roomTypeDao.findById(f.roomTypeId).orElseThrow();
            return persistRoom(project, roomType, new BigDecimal("30.00")).getId();
        });

        // Assign the work to two rooms (seeds the construction placeholder line on both), THEN
        // APPLY_CHEAPEST scoped to the work only (roomId null).
        assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null),
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, roomId2, null),
                EstimateAssignmentService.StagedEdit.applyCheapest(f.workItemId, null)));

        tx().executeWithoutResult(status -> {
            for (Long roomId : List.of(f.roomId, roomId2)) {
                EstimateLineRoomQtyEntity rq = loadRoomQty(f.projectId, f.workItemId, roomId);
                EstimateLineRoomMaterialEntity constructionLine = rq.getMaterials().stream()
                        .filter(m -> m.getBranch() == ConsumptionBranch.construction)
                        .findFirst().orElseThrow();
                assertThat(constructionLine.getConcreteConstructionMaterial())
                        .as("work-scope APPLY_CHEAPEST fills every assigned cell's placeholder (#19)").isNotNull();
                assertThat(constructionLine.getConcreteNet())
                        .as("work-scope APPLY_CHEAPEST collapses to the type's cheapest retailNet (#19)")
                        .isEqualByComparingTo(f.constructionRetailNet);
            }
        });
    }

    @Test
    @DisplayName("staged #19: header-scope APPLY_CHEAPEST (workItemId null, roomId null) is total "
            + "and non-throwing — every assigned placeholder line gets filled (R15.6)")
    void stagedApplyCheapestHeaderScopeIsTotalAndNoThrow() {
        Fixture f = createFixture();

        // ASSIGN then a header-scope APPLY_CHEAPEST (no work, no room) over the whole graph.
        EstimateMatrixDto matrix = assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null),
                EstimateAssignmentService.StagedEdit.applyCheapest(null, null)));

        assertThat(matrix)
                .as("header-scope APPLY_CHEAPEST must be total — the Save returns a matrix (#19, R15.6)")
                .isNotNull();

        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity rq = loadRoomQty(f.projectId, f.workItemId, f.roomId);
            EstimateLineRoomMaterialEntity constructionLine = rq.getMaterials().stream()
                    .filter(m -> m.getBranch() == ConsumptionBranch.construction)
                    .findFirst().orElseThrow();
            assertThat(constructionLine.getConcreteConstructionMaterial())
                    .as("header-scope APPLY_CHEAPEST fills assigned construction placeholders (#19)").isNotNull();
            assertThat(constructionLine.getConcreteNet())
                    .as("header-scope APPLY_CHEAPEST collapses to the type's cheapest retailNet (#19)")
                    .isEqualByComparingTo(f.constructionRetailNet);
        });
    }

    // =====================================================================================
    // Read-model DTO additions: MaterialLineDto.normUnit (#8) + CellDto.formulaKey (#2)
    // =====================================================================================

    @Test
    @DisplayName("assembler #8/#2: an assigned cell exposes the material norm unit code (normUnit) "
            + "and a stable formulaKey the frontend localizes")
    void assembledCellExposesNormUnitAndFormulaKey() {
        Fixture f = createFixture();

        // Assign so the cell + its seeded (construction + finishing) material lines exist, then read
        // the assembled matrix back.
        EstimateMatrixDto matrix = assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null)));

        var cell = findCell(matrix, f.workItemId, f.roomId);
        assertThat(cell).as("the assigned (work, room) cell must exist").isNotNull();
        assertThat(cell.assigned()).isTrue();

        // #8: the material norm unit is resolved from the work's consumption materialUnit ("m2").
        assertThat(cell.materials()).as("the cell must carry its seeded material lines").isNotEmpty();
        assertThat(cell.materials())
                .as("every seeded material line resolves its norm unit code from the consumption (#8)")
                .allSatisfy(line -> assertThat(line.normUnit())
                        .as("normUnit must be the consumption's materialUnit code (#8)").isEqualTo("m2"));

        // #2: the fixture work has NO default formula, so the m2 unit fallback supplies the Volume =>
        // formulaKey is the stable "fallback" key, and formulaUsed (raw text) stays null.
        assertThat(cell.formulaKey())
                .as("with the unit fallback in play, formulaKey is the stable \"fallback\" key (#2)")
                .isEqualTo("fallback");
        assertThat(cell.fallbackUsed())
                .as("the fixture work has no formula => the unit fallback supplied the Volume").isTrue();
        assertThat(cell.formulaUsed())
                .as("formulaUsed (raw formula text) stays null when the fallback was used").isNull();
    }

    // =====================================================================================
    // Per-material-line quantity override (#1) + consumption basis PER_ROOM (#4)
    //
    // Amendment #1: SET_MATERIAL_QUANTITY overrides a single material line's physical quantity
    //   (independent of norm × Volume); the assembled line.quantity reflects the override.
    // Amendment #4: a PER_ROOM consumption yields physical qty = norm (not norm × Volume); the
    //   override still WINS over PER_ROOM.
    //
    // Validates: Requirements 4.1 (one Volume per cell), 6.4 (concrete collapse), 15.2/15.6/15.7.
    // =====================================================================================

    /** Full-arity {@code SET_MATERIAL_QUANTITY} staged edit (amendment #1). */
    private static EstimateAssignmentService.StagedEdit setMaterialQuantity(
            Long workItemId, Long roomId, ConsumptionBranch branch, Long typeId, BigDecimal quantity) {
        return EstimateAssignmentService.StagedEdit.setMaterialQuantity(
                workItemId, roomId, branch, typeId, quantity);
    }

    @Test
    @DisplayName("amendment #1: SET_MATERIAL_QUANTITY overrides a construction line's physical "
            + "quantity, persists manualQty + qtyOverridden, and the assembled line.quantity is the "
            + "override (not norm × Volume)")
    void stagedSetMaterialQuantityOverridesLineQuantity() {
        Fixture f = createFixture();
        BigDecimal override = new BigDecimal("7.0000");

        // ASSIGN (seeds the construction PER_UNIT line: norm 1.5 × Volume 20 = 30) THEN override it.
        EstimateMatrixDto matrix = assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null),
                setMaterialQuantity(f.workItemId, f.roomId, ConsumptionBranch.construction,
                        f.constructionTypeId, override)));

        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity rq = loadRoomQty(f.projectId, f.workItemId, f.roomId);
            EstimateLineRoomMaterialEntity line = rq.getMaterials().stream()
                    .filter(m -> m.getBranch() == ConsumptionBranch.construction)
                    .findFirst().orElseThrow();
            assertThat(line.isQtyOverridden())
                    .as("SET_MATERIAL_QUANTITY must flag the line overridden (#1)").isTrue();
            assertThat(line.getManualQty())
                    .as("SET_MATERIAL_QUANTITY must persist the manual physical quantity (#1)")
                    .isEqualByComparingTo(override);
        });

        var cell = findCell(matrix, f.workItemId, f.roomId);
        var conLine = cell.materials().stream()
                .filter(m -> m.branch() == ConsumptionBranch.construction).findFirst().orElseThrow();
        assertThat(conLine.quantityOverridden())
                .as("the assembled line must expose quantityOverridden = true (#1)").isTrue();
        assertThat(conLine.quantity())
                .as("the assembled resolved quantity must be the manual override, not norm × Volume (#1)")
                .isEqualByComparingTo(override);
    }

    @Test
    @DisplayName("amendment #1: CLEAR_MATERIAL_QUANTITY reverts a line to its derived quantity "
            + "(norm × Volume for a PER_UNIT line)")
    void stagedClearMaterialQuantityRevertsToDerived() {
        Fixture f = createFixture();
        BigDecimal override = new BigDecimal("7.0000");
        // norm 1.5 × Volume 20 (floorArea fallback) = 30.
        BigDecimal derived = new BigDecimal("1.5000").multiply(f.roomFloorArea);

        assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null),
                setMaterialQuantity(f.workItemId, f.roomId, ConsumptionBranch.construction,
                        f.constructionTypeId, override)));

        EstimateMatrixDto matrix = assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.clearMaterialQuantity(
                        f.workItemId, f.roomId, ConsumptionBranch.construction, f.constructionTypeId)));

        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity rq = loadRoomQty(f.projectId, f.workItemId, f.roomId);
            EstimateLineRoomMaterialEntity line = rq.getMaterials().stream()
                    .filter(m -> m.getBranch() == ConsumptionBranch.construction)
                    .findFirst().orElseThrow();
            assertThat(line.isQtyOverridden())
                    .as("CLEAR_MATERIAL_QUANTITY must clear the override flag (#1)").isFalse();
            assertThat(line.getManualQty())
                    .as("CLEAR_MATERIAL_QUANTITY must null the manual quantity (#1)").isNull();
        });

        var cell = findCell(matrix, f.workItemId, f.roomId);
        var conLine = cell.materials().stream()
                .filter(m -> m.branch() == ConsumptionBranch.construction).findFirst().orElseThrow();
        assertThat(conLine.quantity())
                .as("after clear, the resolved quantity reverts to norm × Volume (#1)")
                .isEqualByComparingTo(derived);
    }

    @Test
    @DisplayName("amendment #4: a PER_ROOM construction consumption yields physical qty = norm "
            + "(not norm × Volume), so the assembled line.quantity is the norm and the money band is "
            + "norm × [range]")
    void perRoomConsumptionYieldsNormQuantity() {
        Fixture f = createFixture();
        BigDecimal norm = new BigDecimal("1.5000"); // the construction consumption norm

        // Flip the fixture's construction consumption to PER_ROOM before assigning (so assign copies
        // PER_ROOM onto the seeded line).
        tx().executeWithoutResult(status -> {
            WorkMaterialConsumptionEntity consumption = entityManager.createQuery(
                            "select c from WorkMaterialConsumptionEntity c "
                                    + "where c.workItem.id = :wid and c.branch = :branch",
                            WorkMaterialConsumptionEntity.class)
                    .setParameter("wid", f.workItemId)
                    .setParameter("branch", ConsumptionBranch.construction)
                    .getResultList().get(0);
            consumption.setConsumptionBasis(com.foremen.dao.model.ConsumptionBasis.PER_ROOM);
            entityManager.merge(consumption);
        });

        EstimateMatrixDto matrix = assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null)));

        // The seeded line carries the copied PER_ROOM basis.
        tx().executeWithoutResult(status -> {
            EstimateLineRoomQtyEntity rq = loadRoomQty(f.projectId, f.workItemId, f.roomId);
            EstimateLineRoomMaterialEntity line = rq.getMaterials().stream()
                    .filter(m -> m.getBranch() == ConsumptionBranch.construction)
                    .findFirst().orElseThrow();
            assertThat(line.getConsumptionBasis())
                    .as("assign must copy the PER_ROOM consumption basis onto the frozen line (#4)")
                    .isEqualTo(com.foremen.dao.model.ConsumptionBasis.PER_ROOM);
        });

        var cell = findCell(matrix, f.workItemId, f.roomId);
        var conLine = cell.materials().stream()
                .filter(m -> m.branch() == ConsumptionBranch.construction).findFirst().orElseThrow();
        assertThat(conLine.consumptionBasis())
                .as("the assembled line exposes PER_ROOM (#4)")
                .isEqualTo(com.foremen.dao.model.ConsumptionBasis.PER_ROOM);
        assertThat(conLine.quantity())
                .as("a PER_ROOM line's resolved quantity is norm × 1 = norm, independent of Volume (#4)")
                .isEqualByComparingTo(norm);
        assertThat(conLine.quantityOverridden())
                .as("no manual override set -> quantityOverridden = false (#4)").isFalse();
    }

    @Test
    @DisplayName("amendments #1 + #4: a manual SET_MATERIAL_QUANTITY override WINS over a PER_ROOM "
            + "basis — the resolved quantity is the manual value, not the norm")
    void manualOverrideWinsOverPerRoomBasis() {
        Fixture f = createFixture();
        BigDecimal override = new BigDecimal("3.0000");

        tx().executeWithoutResult(status -> {
            WorkMaterialConsumptionEntity consumption = entityManager.createQuery(
                            "select c from WorkMaterialConsumptionEntity c "
                                    + "where c.workItem.id = :wid and c.branch = :branch",
                            WorkMaterialConsumptionEntity.class)
                    .setParameter("wid", f.workItemId)
                    .setParameter("branch", ConsumptionBranch.construction)
                    .getResultList().get(0);
            consumption.setConsumptionBasis(com.foremen.dao.model.ConsumptionBasis.PER_ROOM);
            entityManager.merge(consumption);
        });

        // ASSIGN (PER_ROOM line, resolved qty = norm 1.5) THEN override to 3.0.
        EstimateMatrixDto matrix = assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.roomId, null),
                setMaterialQuantity(f.workItemId, f.roomId, ConsumptionBranch.construction,
                        f.constructionTypeId, override)));

        var cell = findCell(matrix, f.workItemId, f.roomId);
        var conLine = cell.materials().stream()
                .filter(m -> m.branch() == ConsumptionBranch.construction).findFirst().orElseThrow();
        assertThat(conLine.consumptionBasis())
                .as("the line is still PER_ROOM (#4)")
                .isEqualTo(com.foremen.dao.model.ConsumptionBasis.PER_ROOM);
        assertThat(conLine.quantityOverridden())
                .as("the line is manually overridden (#1)").isTrue();
        assertThat(conLine.quantity())
                .as("the manual override WINS over the PER_ROOM basis (#1 > #4)")
                .isEqualByComparingTo(override);
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
        return createFixture(true);
    }

    /**
     * Builds the estimate graph, optionally creating the project's DRAFT estimate up front. When
     * {@code createEstimate} is {@code false} the project has NO estimate row yet — mirroring an
     * existing project created before the estimate feature — so a first matrix read must
     * get-or-create it (R1.7).
     */
    private Fixture createFixture(boolean createEstimate) {
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

            // Create the DRAFT estimate for the project (get-or-create) — skipped when the test
            // wants a project with NO estimate yet (R1.7).
            if (createEstimate) {
                estimateService.getOrCreateForProject(project.getId());
            }

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

    // =====================================================================================
    // Package finishing-materials merge (FOR-05-05 Amendment A1): apply-package water-fill.
    //
    // A package's assortment group (room-type-scoped) carries a project-wide referenceQty of a
    // finishing material TYPE with a per-package price band. Applying the package merges that total
    // across the applicable rooms that consume the type via WATER-FILL (smallest-need-first, capped
    // at need, leftover discarded): a fully-covered room gets a package-flagged line = its need with
    // NO extra; a partially-covered room gets a package line = alloc + an extra line = need - alloc.
    // Re-applying the same package is idempotent; applying a different package replaces the lines.
    //
    // Validates: FOR-05-05 Amendment A1 (distribution algorithm, re-apply semantics, DTO additions).
    // =====================================================================================

    /** The worked-example fixture: two rooms (need 10, need 15) + a package whose group total = 20. */
    private static final class MergeFixture {
        Long projectId;
        Long smallRoomId;   // need 10 (fully covered by the package)
        Long largeRoomId;   // need 15 (covered 10 by the package + 5 extra)
        Long workItemId;
        String packageCode;
        String otherPackageCode;
    }

    /**
     * Builds the merge fixture: an {@code m2}-unit work with a finishing consumption of norm 1.0 so a
     * room's NEED equals its floorArea (small = 10, large = 15). An assortment GROUP scoped to the
     * rooms' room type, with a single position (the finishing type) and a per-package price band, and
     * a package {@code referenceQty = 20}. The work is a package member so apply-package assigns it.
     */
    private MergeFixture createMergeFixture() {
        MergeFixture f = new MergeFixture();
        tx().executeWithoutResult(status -> {
            CurrencyEntity currency = persistCurrency();
            MeasurementUnitEntity m2Unit = persistMeasurementUnit("m2");

            WorkCategoryEntity category = persistWorkCategory();
            WorkItemEntity workItem = persistWorkItem("WM_" + runId, category, m2Unit);
            persistWorkPrice(workItem, currency, new BigDecimal("100.00"));

            // Finishing consumption norm 1.0 -> NEED == floorArea (PER_UNIT norm × Volume).
            MaterialTypeEntity finishingType = persistMaterialType("FTM_" + runId);
            persistFinishingMaterial(finishingType, m2Unit, new BigDecimal("60.00"));
            persistFinishingConsumptionNorm(workItem, finishingType, m2Unit, new BigDecimal("1.0000"));

            ProjectEntity project = persistProject();
            RoomTypeEntity roomType = persistRoomType();
            RoomEntity small = persistRoom(project, roomType, new BigDecimal("10.00"));
            RoomEntity large = persistRoom(project, roomType, new BigDecimal("15.00"));

            estimateService.getOrCreateForProject(project.getId());

            // Package + membership so apply-package assigns the work to both rooms.
            OfferPackageEntity pkg = persistOfferPackage("PKG_" + runId);
            persistMembership(workItem, pkg);
            OfferPackageEntity otherPkg = persistOfferPackage("PKG2_" + runId);
            persistMembership(workItem, otherPkg);

            // Assortment group (scoped to the rooms' room type) with the finishing-type position,
            // referenceQty 20, and a per-package price band for BOTH packages.
            AssortmentGroupEntity group = persistAssortmentGroup(new BigDecimal("20.0000"), roomType);
            AssortmentPositionEntity position = persistAssortmentPosition(group, finishingType);
            persistAssortmentPrice(position, pkg, new BigDecimal("50.00"), new BigDecimal("70.00"));
            persistAssortmentPrice(position, otherPkg, new BigDecimal("55.00"), new BigDecimal("75.00"));

            f.projectId = project.getId();
            f.smallRoomId = small.getId();
            f.largeRoomId = large.getId();
            f.workItemId = workItem.getId();
            f.packageCode = pkg.getCode();
            f.otherPackageCode = otherPkg.getCode();
        });
        return f;
    }

    private void persistFinishingConsumptionNorm(
            WorkItemEntity workItem, MaterialTypeEntity type, MeasurementUnitEntity materialUnit, BigDecimal norm) {
        WorkMaterialConsumptionEntity consumption = new WorkMaterialConsumptionEntity();
        consumption.setWorkItem(workItem);
        consumption.setMaterialUnit(materialUnit);
        consumption.setBranch(ConsumptionBranch.finishing);
        consumption.setFinishingMaterialType(type);
        consumption.setNormQty(norm);
        consumption.setSourceType("MANUAL");
        consumption.setSourceDoc("test");
        consumption.setSourceRef("test");
        workMaterialConsumptionDao.save(consumption);
    }

    private OfferPackageEntity persistOfferPackage(String code) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode(code);
        pkg.setOrderNo(1);
        pkg.setNameRU("Пакет " + code);
        pkg.setNamePL("Pakiet " + code);
        pkg.setActive(true);
        return offerPackageDao.save(pkg);
    }

    private void persistMembership(WorkItemEntity workItem, OfferPackageEntity pkg) {
        WorkPackageOverrideEntity override = new WorkPackageOverrideEntity();
        override.setWorkItem(workItem);
        override.setOfferPackage(pkg);
        override.setMember(Boolean.TRUE);
        workPackageOverrideDao.save(override);
    }

    private AssortmentGroupEntity persistAssortmentGroup(BigDecimal referenceQty, RoomTypeEntity roomType) {
        AssortmentGroupEntity group = new AssortmentGroupEntity();
        group.setNameRU("Группа " + runId);
        group.setNamePL("Grupa " + runId);
        group.setSortOrder(1);
        group.setReferenceQty(referenceQty);
        group.setReferenceUnit("m2");
        group.getRoomTypes().add(roomType);
        return assortmentGroupDao.save(group);
    }

    private AssortmentPositionEntity persistAssortmentPosition(
            AssortmentGroupEntity group, MaterialTypeEntity type) {
        // The apply-package merge resolves applicable rooms via the group's room types, NOT via a
        // position->work link (FOR-05-05 Wave 1b, #8 moved the link per-package and the merge never
        // consumed it), so the fixture position needs no work-item link.
        AssortmentPositionEntity position = new AssortmentPositionEntity();
        position.setGroup(group);
        position.setMaterialType(type);
        position.setSortOrder(1);
        return assortmentPositionDao.save(position);
    }

    private void persistAssortmentPrice(
            AssortmentPositionEntity position, OfferPackageEntity pkg, BigDecimal min, BigDecimal max) {
        AssortmentPositionPriceEntity price = new AssortmentPositionPriceEntity();
        price.setPosition(position);
        price.setOfferPackage(pkg);
        price.setMinPrice(min);
        price.setAvgPrice(min.add(max).divide(new BigDecimal("2")));
        price.setMaxPrice(max);
        assortmentPositionPriceDao.save(price);
    }

    /** The finishing material lines of a (work, room) cell, keyed by whether they came from a package. */
    private List<EstimateLineRoomMaterialEntity> finishingLines(Long projectId, Long workItemId, Long roomId) {
        return entityManager.createQuery(
                        "select m from EstimateLineRoomMaterialEntity m "
                                + "where m.branch = :branch "
                                + "and m.roomQty.line.workItem.id = :workItemId "
                                + "and m.roomQty.room.id = :roomId "
                                + "and m.roomQty.line.estimate.project.id = :projectId",
                        EstimateLineRoomMaterialEntity.class)
                .setParameter("branch", ConsumptionBranch.finishing)
                .setParameter("workItemId", workItemId)
                .setParameter("roomId", roomId)
                .setParameter("projectId", projectId)
                .getResultList();
    }

    @Test
    @DisplayName("A1 merge: applying a package water-fills the package finishing total across the "
            + "consuming rooms — a fully-covered room gets ONE package line (= its need, no extra), a "
            + "partially-covered room gets a package line (= alloc) + an extra line (= need - alloc)")
    void applyPackageMergesFinishingWithWaterFill() {
        MergeFixture f = createMergeFixture();

        // Apply the package: assign the member work to both rooms THEN merge the package finishing.
        assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.smallRoomId, f.packageCode),
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.largeRoomId, f.packageCode),
                EstimateAssignmentService.StagedEdit.mergePackageMaterials(f.packageCode)));

        tx().executeWithoutResult(status -> {
            // Small room (need 10): fully covered -> ONE package-flagged line = 10, NO extra.
            List<EstimateLineRoomMaterialEntity> small = finishingLines(f.projectId, f.workItemId, f.smallRoomId);
            assertThat(small).as("small room keeps exactly one finishing line (the package line)").hasSize(1);
            EstimateLineRoomMaterialEntity smallLine = small.get(0);
            assertThat(smallLine.isAppliedFromPackage())
                    .as("the small room's finishing line is package-flagged").isTrue();
            assertThat(smallLine.getManualQty())
                    .as("the package line's fixed quantity is the full need (10)")
                    .isEqualByComparingTo(new BigDecimal("10"));
            assertThat(smallLine.isQtyOverridden()).isTrue();
            assertThat(smallLine.getRangeMin()).as("the package band min").isEqualByComparingTo("50.00");
            assertThat(smallLine.getRangeMax()).as("the package band max").isEqualByComparingTo("70.00");

            // Large room (need 15): package alloc 10 + extra 5.
            List<EstimateLineRoomMaterialEntity> large = finishingLines(f.projectId, f.workItemId, f.largeRoomId);
            assertThat(large).as("large room has a package line AND an extra line").hasSize(2);
            EstimateLineRoomMaterialEntity pkgLine = large.stream()
                    .filter(EstimateLineRoomMaterialEntity::isAppliedFromPackage).findFirst().orElseThrow();
            EstimateLineRoomMaterialEntity extraLine = large.stream()
                    .filter(m -> !m.isAppliedFromPackage()).findFirst().orElseThrow();
            assertThat(pkgLine.getManualQty())
                    .as("the large room's package allocation is 10 (remaining after the small room)")
                    .isEqualByComparingTo(new BigDecimal("10"));
            assertThat(extraLine.getManualQty())
                    .as("the large room's extra carries only the uncovered remainder (15 - 10 = 5)")
                    .isEqualByComparingTo(new BigDecimal("5"));
            assertThat(extraLine.isQtyOverridden())
                    .as("the extra line's quantity is fixed to the remainder").isTrue();
        });
    }

    @Test
    @DisplayName("A1 merge: the matrix DTO surfaces appliedFromPackage on package lines and aggregates "
            + "packageVolume per row / group and packageMaterialsTotal at the header")
    void applyPackageSurfacesOnMatrixDto() {
        MergeFixture f = createMergeFixture();

        EstimateMatrixDto matrix = assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.smallRoomId, f.packageCode),
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.largeRoomId, f.packageCode),
                EstimateAssignmentService.StagedEdit.mergePackageMaterials(f.packageCode)));

        // At least one material line surfaces appliedFromPackage = true on the assembled matrix.
        boolean anyPackageLine = matrix.groups().stream()
                .flatMap(g -> g.rows().stream())
                .flatMap(r -> r.cells().stream())
                .flatMap(c -> c.materials().stream())
                .anyMatch(com.foremen.service.estimate.matrix.MaterialLineDto::appliedFromPackage);
        assertThat(anyPackageLine)
                .as("package-flagged material lines surface appliedFromPackage on the matrix DTO").isTrue();

        // packageMaterialsTotal = Σ package-allocated volume = 10 (small) + 10 (large) = 20.
        assertThat(matrix.packageMaterialsTotal())
                .as("the header package total is the sum of the package allocations (10 + 10 = 20)")
                .isEqualByComparingTo(new BigDecimal("20"));

        // The one work row's packageVolume also aggregates to 20, and the group subtotal matches.
        var row = matrix.groups().stream()
                .flatMap(g -> g.rows().stream())
                .filter(r -> f.workItemId.equals(r.workItemId()))
                .findFirst().orElseThrow();
        assertThat(row.packageVolume())
                .as("the work row's packageVolume aggregates its cells' package allocations (20)")
                .isEqualByComparingTo(new BigDecimal("20"));
        var group = matrix.groups().stream()
                .filter(g -> g.rows().stream().anyMatch(r -> f.workItemId.equals(r.workItemId())))
                .findFirst().orElseThrow();
        assertThat(group.packageVolume())
                .as("the group's packageVolume subtotal equals the sum of its rows' packageVolume (20)")
                .isEqualByComparingTo(new BigDecimal("20"));
        // The package money contribution is a real band (point F): 20 units × [50..70] band edges.
        assertThat(matrix.packageMaterialsMoney()).as("the package money sum is present").isNotNull();
        assertThat(matrix.packageMaterialsMoney().min())
                .as("package money min = 10×50 + 10×50 = 1000").isEqualByComparingTo("1000.00");
        assertThat(matrix.packageMaterialsMoney().max())
                .as("package money max = 10×70 + 10×70 = 1400").isEqualByComparingTo("1400.00");

        // FOR-05-04 Change #4: the per-row / per-group packageMoney mirrors the header band while the
        // package lines are placeholders (nothing concrete chosen) — a real band, min != max.
        assertThat(row.packageMoney()).as("the work row's packageMoney is present").isNotNull();
        assertThat(row.packageMoney().min())
                .as("row packageMoney min = 10×50 + 10×50 = 1000 (placeholder ⇒ band)")
                .isEqualByComparingTo("1000.00");
        assertThat(row.packageMoney().max())
                .as("row packageMoney max = 10×70 + 10×70 = 1400 (placeholder ⇒ band)")
                .isEqualByComparingTo("1400.00");
        assertThat(row.packageMoney().min())
                .as("placeholder package lines yield a band on the row (min != max)")
                .isNotEqualByComparingTo(row.packageMoney().max());
        assertThat(group.packageMoney().min())
                .as("group packageMoney = Σ of its rows' packageMoney min (1000)")
                .isEqualByComparingTo("1000.00");
        assertThat(group.packageMoney().max())
                .as("group packageMoney = Σ of its rows' packageMoney max (1400)")
                .isEqualByComparingTo("1400.00");
    }

    @Test
    @DisplayName("FOR-05-04 #4: a row whose package-flagged finishing lines are all CONCRETE yields a "
            + "collapsed packageMoney (min == max), mirroring the cost collapse rule")
    void packageMoneyCollapsesWhenPackageLinesConcrete() {
        MergeFixture f = createMergeFixture();

        // Apply the package to both rooms, then fill EVERY placeholder line of the work with its
        // cheapest concrete product (work-scope APPLY_CHEAPEST, roomId == null). This collapses each
        // package-flagged finishing line to its concrete retailNet, so the row's packageMoney
        // collapses to a point.
        EstimateMatrixDto matrix = assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.smallRoomId, f.packageCode),
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.largeRoomId, f.packageCode),
                EstimateAssignmentService.StagedEdit.mergePackageMaterials(f.packageCode),
                EstimateAssignmentService.StagedEdit.applyCheapest(f.workItemId, null)));

        var row = matrix.groups().stream()
                .flatMap(g -> g.rows().stream())
                .filter(r -> f.workItemId.equals(r.workItemId()))
                .findFirst().orElseThrow();
        var group = matrix.groups().stream()
                .filter(g -> g.rows().stream().anyMatch(r -> f.workItemId.equals(r.workItemId())))
                .findFirst().orElseThrow();

        // Every package-flagged finishing line now has a concrete product (retailNet 60), so the
        // per-row / per-group packageMoney collapses to a point: 20 units × 60 = 1200.
        assertThat(row.packageMoney().min())
                .as("concrete package lines collapse the row's packageMoney to a point (min == max)")
                .isEqualByComparingTo(row.packageMoney().max());
        assertThat(row.packageMoney().min())
                .as("row packageMoney collapses to 20 × 60 = 1200").isEqualByComparingTo("1200.00");
        assertThat(group.packageMoney().min())
                .as("group packageMoney collapses too (min == max)")
                .isEqualByComparingTo(group.packageMoney().max());
        assertThat(group.packageMoney().min())
                .as("group packageMoney collapses to 1200").isEqualByComparingTo("1200.00");
    }

    @Test
    @DisplayName("A1 merge: re-applying the SAME package is idempotent — the package/extra split is "
            + "unchanged (no duplicate package lines accumulate)")
    void reapplyingSamePackageIsIdempotent() {
        MergeFixture f = createMergeFixture();

        assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.smallRoomId, f.packageCode),
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.largeRoomId, f.packageCode),
                EstimateAssignmentService.StagedEdit.mergePackageMaterials(f.packageCode)));

        // Re-apply the same package (assigns are no-ops on already-assigned cells; the merge re-runs).
        assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.smallRoomId, f.packageCode),
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.largeRoomId, f.packageCode),
                EstimateAssignmentService.StagedEdit.mergePackageMaterials(f.packageCode)));

        tx().executeWithoutResult(status -> {
            List<EstimateLineRoomMaterialEntity> small = finishingLines(f.projectId, f.workItemId, f.smallRoomId);
            assertThat(small).as("re-apply must not duplicate the small room's package line").hasSize(1);
            assertThat(small.get(0).getManualQty()).isEqualByComparingTo(new BigDecimal("10"));

            List<EstimateLineRoomMaterialEntity> large = finishingLines(f.projectId, f.workItemId, f.largeRoomId);
            assertThat(large).as("re-apply must keep exactly the package + extra split").hasSize(2);
            assertThat(large.stream().filter(EstimateLineRoomMaterialEntity::isAppliedFromPackage).count())
                    .as("exactly one package line after re-apply").isEqualTo(1L);
        });
    }

    @Test
    @DisplayName("A1 merge: applying a DIFFERENT package replaces the previous package-flagged lines "
            + "with the new package's band (and the split is recomputed)")
    void applyingDifferentPackageReplacesPackageLines() {
        MergeFixture f = createMergeFixture();

        assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.smallRoomId, f.packageCode),
                EstimateAssignmentService.StagedEdit.assign(f.workItemId, f.largeRoomId, f.packageCode),
                EstimateAssignmentService.StagedEdit.mergePackageMaterials(f.packageCode)));

        // Apply the OTHER package (same assortment total, different band 55..75).
        assignmentService.saveAndAssemble(f.projectId, List.of(
                EstimateAssignmentService.StagedEdit.mergePackageMaterials(f.otherPackageCode)));

        tx().executeWithoutResult(status -> {
            List<EstimateLineRoomMaterialEntity> small = finishingLines(f.projectId, f.workItemId, f.smallRoomId);
            assertThat(small).as("still exactly one package line after switching packages").hasSize(1);
            EstimateLineRoomMaterialEntity smallLine = small.get(0);
            assertThat(smallLine.isAppliedFromPackage()).isTrue();
            assertThat(smallLine.getRangeMin())
                    .as("the package line now carries the NEW package's band min (55)")
                    .isEqualByComparingTo("55.00");
            assertThat(smallLine.getRangeMax())
                    .as("the package line now carries the NEW package's band max (75)")
                    .isEqualByComparingTo("75.00");

            long packageLines = entityManager.createQuery(
                            "select count(m) from EstimateLineRoomMaterialEntity m "
                                    + "where m.appliedFromPackage = true "
                                    + "and m.roomQty.line.estimate.project.id = :projectId",
                            Long.class)
                    .setParameter("projectId", f.projectId)
                    .getSingleResult();
            assertThat(packageLines)
                    .as("switching packages replaces (does not accumulate) package lines: still 2 (one per room)")
                    .isEqualTo(2L);
        });
    }
}
