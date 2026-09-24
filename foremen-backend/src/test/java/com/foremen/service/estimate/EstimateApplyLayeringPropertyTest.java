package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.foremen.dao.AssortmentPositionPriceDao;
import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.EstimateLineRoomQtyDao;
import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.WorkPackageOverrideDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.WorkVolumeFormulaDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPackageOverrideEntity;
import com.foremen.dao.model.WorkVolumeFormulaEntity;
import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.service.estimate.EstimateAssignmentService.AssignmentPlan;
import com.foremen.service.estimate.EstimateAssignmentService.CalculatedApply;
import com.foremen.service.pricing.FinishingPriceRangeResolver;
import com.foremen.service.pricing.PriceRangeResolver;

import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based test for the <b>apply calculate core</b> of {@link EstimateAssignmentService}
 * (FOR-05-05, task 5.4; design §B4) — the read-only {@code calculateApplyWorkToRooms} /
 * {@code calculateApplyPackage} preview that plans which cells a work-row / package apply would
 * stage.
 *
 * <p>The core is exercised through the public {@code calculate*} methods against an in-memory entity
 * graph wired via mocked DAOs (the pure planning helpers {@code planWorkOverRooms},
 * {@code existingAssignments}, {@code roomAttaches}, {@code memberWorksOf} are private, so this
 * observes their combined result — the returned {@link CalculatedApply} plan). Two invariants are
 * asserted over 100+ generated room/attachment/existing-assignment shapes:
 *
 * <ol>
 *   <li><b>Attachment by room type (R10.2, R10.3, R11.4).</b> A planned cell's room is always one the
 *       work attaches to — every room when the work's {@code Room_Type_Attachment} is empty, else only
 *       rooms whose type is in the attachment.</li>
 *   <li><b>Layering without overriding (R9.5, R9.6, R11.2, R11.5).</b> The plan contains exactly the
 *       attachable rooms that are NOT already assigned to the work; an already-assigned cell is never
 *       in the plan (its existing Volume is left untouched), and every attachable non-assigned room
 *       appears exactly once with a freshly resolved Volume.</li>
 * </ol>
 *
 * <p>and the defining trait of a calculate core (R15.3, R15.6): it <b>persists nothing</b> — the plan
 * is a staged set written only on the batched Save. This is verified by asserting that no write DAO,
 * no {@code EntityManager}, and no recompute is touched during the compute, and that the result maps
 * to staged edits (never an immediate server write).
 *
 * <p>Feature: for-05-05-bill-of-materials, Property 6: Apply attaches by room type and layers without
 * overriding existing volumes
 *
 * <p><b>Validates: Requirements 9.5, 9.6, 10.2, 10.3, 11.2, 11.4, 11.5, 15.3, 15.6</b>
 */
// Feature: for-05-05-bill-of-materials, Property 6: Apply attaches by room type and layers without overriding existing volumes
@Tag("Feature: for-05-05-bill-of-materials, Property 6: Apply attaches by room type and layers without overriding existing volumes")
class EstimateApplyLayeringPropertyTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long WORK_ID = 7L;
    private static final String UNIT_CODE = "m2";

    // Write-path DAOs / collaborators that a *calculate* core MUST NOT touch (non-persistence proof).
    private EstimateLineDao estimateLineDao;
    private EstimateLineRoomQtyDao estimateLineRoomQtyDao;
    private EstimateLineRoomMaterialDao estimateLineRoomMaterialDao;
    private EntityManager entityManager;
    private EstimateRecomputeService estimateRecomputeService;

    private EstimateAssignmentService service;

    // Read DAOs the calculate core consults; re-stubbed per iteration by buildService(...).
    private EstimateDao estimateDao;
    private RoomDao roomDao;
    private WorkItemDao workItemDao;
    private WorkVolumeFormulaDao workVolumeFormulaDao;
    private WorkPackageOverrideDao workPackageOverrideDao;
    private OfferPackageDao offerPackageDao;
    private WorkMaterialConsumptionDao workMaterialConsumptionDao;
    private ConstructionMaterialDao constructionMaterialDao;
    private FinishingMaterialDao finishingMaterialDao;
    private AssortmentPositionPriceDao assortmentPositionPriceDao;

    // ------------------------------------------------------------------------------------------
    // Property 6 — work-row apply: attaches by room type and layers without overriding volumes
    // Validates: Requirements 9.5, 9.6, 10.2, 10.3, 11.4, 11.5, 15.3, 15.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: for-05-05-bill-of-materials, Property 6: Apply attaches by room type and layers without overriding existing volumes")
    void workRowApplyAttachesByTypeAndLayersWithoutOverriding(
            @ForAll("rooms") List<RoomSpec> roomSpecs,
            @ForAll("attachment") Set<Long> attachedTypeIds,
            @ForAll("existing") Set<Long> preAssignedRoomIndexes) {

        // --- Build the in-memory graph -------------------------------------------------------
        List<RoomEntity> rooms = buildRooms(roomSpecs);

        WorkItemEntity work = work(WORK_ID, attachedTypeIds);
        WorkVolumeFormulaEntity defaultFormula = floorAreaFormula(work); // Volume := room.floorArea

        // Pre-existing assignments of THIS work to a subset of the rooms (by list index).
        Set<Long> existingRoomIds = new HashSet<>();
        for (Long idx : preAssignedRoomIndexes) {
            if (idx >= 0 && idx < rooms.size()) {
                existingRoomIds.add(rooms.get(idx.intValue()).getId());
            }
        }
        EstimateEntity estimate = estimateWithAssignments(work, rooms, existingRoomIds);

        buildService(estimate, rooms, work, defaultFormula, /* override */ null);

        // --- Exercise the calculate core (no package -> default formula, no assortment) ------
        CalculatedApply plan = service.calculateApplyWorkToRooms(PROJECT_ID, WORK_ID, null);

        // --- Non-persistence: a calculate core writes NOTHING (R15.3, R15.6) -----------------
        assertNoPersistence();

        // --- Attachment + layering invariants ------------------------------------------------
        Set<Long> attachedTypes = attachedTypeIds.isEmpty() ? null : attachedTypeIds;
        Set<Long> plannedRoomIds = new HashSet<>();
        for (AssignmentPlan p : plan.assignments()) {
            assertThat(p.workItemId()).isEqualTo(WORK_ID);

            RoomEntity room = roomById(rooms, p.roomId());
            assertThat(room).as("planned room must exist").isNotNull();

            // (R10.2/R10.3/R11.4) planned room attaches: all rooms when empty, else only attached types.
            if (attachedTypes != null) {
                assertThat(room.getRoomType()).isNotNull();
                assertThat(attachedTypes).contains(room.getRoomType().getId());
            }
            // (R9.6/R11.5) layering: an already-assigned cell is never (re)planned -> not overridden.
            assertThat(existingRoomIds).doesNotContain(p.roomId());

            // The freshly-resolved Volume equals the default formula's value (room.floorArea).
            assertThat(p.volume()).isEqualByComparingTo(room.getFloorArea());
            assertThat(p.fallbackUsed()).isFalse();

            // no duplicate cell in the plan
            assertThat(plannedRoomIds.add(p.roomId())).as("cell planned at most once").isTrue();
        }

        // Completeness: EVERY attachable, not-yet-assigned room is planned exactly once (R11.2/R9.5).
        Set<Long> expected = new HashSet<>();
        for (RoomEntity room : rooms) {
            boolean attaches = attachedTypes == null
                    || (room.getRoomType() != null && attachedTypes.contains(room.getRoomType().getId()));
            if (attaches && !existingRoomIds.contains(room.getId())) {
                expected.add(room.getId());
            }
        }
        assertThat(plannedRoomIds).isEqualTo(expected);

        // Every plan maps to a staged edit (written only on Save, never an immediate server write).
        assertThat(plan.toStagedEdits()).hasSize(plan.assignments().size());
    }

    // ------------------------------------------------------------------------------------------
    // Property 6 — package apply: the same attachment + layering over the package's member works
    // Validates: Requirements 9.5, 9.6, 10.2, 10.3, 11.2, 11.4, 11.5, 15.3, 15.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: for-05-05-bill-of-materials, Property 6: Apply attaches by room type and layers without overriding existing volumes")
    void packageApplyAttachesByTypeAndLayersWithoutOverriding(
            @ForAll("rooms") List<RoomSpec> roomSpecs,
            @ForAll("attachment") Set<Long> attachedTypeIds,
            @ForAll("existing") Set<Long> preAssignedRoomIndexes) {

        List<RoomEntity> rooms = buildRooms(roomSpecs);

        WorkItemEntity work = work(WORK_ID, attachedTypeIds);
        WorkVolumeFormulaEntity defaultFormula = floorAreaFormula(work);

        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setId(3L);
        pkg.setCode("STANDARD");
        pkg.setOrderNo(1);
        pkg.setNameRU("ru");
        pkg.setNamePL("pl");

        // A package override marking the work a member of the package. No override AST -> default
        // formula still governs the Volume (attachment is what the package apply drives).
        WorkPackageOverrideEntity override = new WorkPackageOverrideEntity();
        override.setId(11L);
        override.setWorkItem(work);
        override.setOfferPackage(pkg);
        override.setMember(Boolean.TRUE);

        Set<Long> existingRoomIds = new HashSet<>();
        for (Long idx : preAssignedRoomIndexes) {
            if (idx >= 0 && idx < rooms.size()) {
                existingRoomIds.add(rooms.get(idx.intValue()).getId());
            }
        }
        EstimateEntity estimate = estimateWithAssignments(work, rooms, existingRoomIds);

        buildService(estimate, rooms, work, defaultFormula, override);
        lenient().when(offerPackageDao.findByCode("STANDARD")).thenReturn(Optional.of(pkg));
        lenient().when(workPackageOverrideDao.findAllWithWorkItemAndPackage())
                .thenReturn(List.of(override));
        lenient().when(assortmentPositionPriceDao.findAll()).thenReturn(List.of());

        CalculatedApply plan = service.calculateApplyPackage(PROJECT_ID, "STANDARD");

        assertNoPersistence();

        Set<Long> attachedTypes = attachedTypeIds.isEmpty() ? null : attachedTypeIds;
        Set<Long> plannedRoomIds = new HashSet<>();
        for (AssignmentPlan p : plan.assignments()) {
            assertThat(p.workItemId()).isEqualTo(WORK_ID);
            RoomEntity room = roomById(rooms, p.roomId());
            assertThat(room).isNotNull();
            if (attachedTypes != null) {
                assertThat(room.getRoomType()).isNotNull();
                assertThat(attachedTypes).contains(room.getRoomType().getId());
            }
            assertThat(existingRoomIds).doesNotContain(p.roomId());
            assertThat(p.volume()).isEqualByComparingTo(room.getFloorArea());
            assertThat(plannedRoomIds.add(p.roomId())).isTrue();
        }

        Set<Long> expected = new HashSet<>();
        for (RoomEntity room : rooms) {
            boolean attaches = attachedTypes == null
                    || (room.getRoomType() != null && attachedTypes.contains(room.getRoomType().getId()));
            if (attaches && !existingRoomIds.contains(room.getId())) {
                expected.add(room.getId());
            }
        }
        assertThat(plannedRoomIds).isEqualTo(expected);
        assertThat(plan.toStagedEdits()).hasSize(plan.assignments().size());
    }

    // ------------------------------------------------------------------------------------------
    // Non-persistence assertion (the calculate-core defining trait, R15.3/R15.6)
    // ------------------------------------------------------------------------------------------

    private void assertNoPersistence() {
        verifyNoInteractions(
                estimateLineDao,
                estimateLineRoomQtyDao,
                estimateLineRoomMaterialDao,
                entityManager,
                estimateRecomputeService);
    }

    // ------------------------------------------------------------------------------------------
    // Service assembly — mock every DAO; stub only what the calculate core reads
    // ------------------------------------------------------------------------------------------

    private void buildService(
            EstimateEntity estimate,
            List<RoomEntity> rooms,
            WorkItemEntity work,
            WorkVolumeFormulaEntity defaultFormula,
            WorkPackageOverrideEntity override) {

        estimateDao = mock(EstimateDao.class);
        roomDao = mock(RoomDao.class);
        workItemDao = mock(WorkItemDao.class);
        workVolumeFormulaDao = mock(WorkVolumeFormulaDao.class);
        workPackageOverrideDao = mock(WorkPackageOverrideDao.class);
        offerPackageDao = mock(OfferPackageDao.class);
        workMaterialConsumptionDao = mock(WorkMaterialConsumptionDao.class);
        constructionMaterialDao = mock(ConstructionMaterialDao.class);
        finishingMaterialDao = mock(FinishingMaterialDao.class);
        assortmentPositionPriceDao = mock(AssortmentPositionPriceDao.class);

        // Write-path collaborators: mocked but NEVER expected to be invoked by a calculate core.
        estimateLineDao = mock(EstimateLineDao.class);
        estimateLineRoomQtyDao = mock(EstimateLineRoomQtyDao.class);
        estimateLineRoomMaterialDao = mock(EstimateLineRoomMaterialDao.class);
        entityManager = mock(EntityManager.class);
        estimateRecomputeService = mock(EstimateRecomputeService.class);

        // --- read stubs the planning core consults ---
        lenient().when(estimateDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(estimate));
        lenient().when(roomDao.findByProjectId(PROJECT_ID)).thenReturn(rooms);
        lenient().when(workItemDao.findById(WORK_ID)).thenReturn(Optional.of(work));
        lenient().when(workVolumeFormulaDao.findByWorkItemId(WORK_ID))
                .thenReturn(Optional.ofNullable(defaultFormula));
        lenient().when(workPackageOverrideDao.findByWorkItemIdAndOfferPackageId(anyLong(), anyLong()))
                .thenReturn(Optional.ofNullable(override));
        // No material consumptions -> no material-line planning noise for this attachment/layering test.
        lenient().when(workMaterialConsumptionDao.findByWorkItemIdIn(any())).thenReturn(List.of());
        lenient().when(constructionMaterialDao.findByActiveTrueAndRetailNetNotNull()).thenReturn(List.of());
        lenient().when(finishingMaterialDao.findByActiveTrueAndRetailNetNotNull()).thenReturn(List.of());

        service = new EstimateAssignmentService(
                estimateLineRoomMaterialDao,
                /* mapper                 */ mock(com.foremen.service.model.mapper.EstimateLineRoomMaterialServiceMapper.class),
                /* projectAccessCache     */ mock(com.foremen.service.ProjectAccessCache.class),
                /* auditLogDao            */ mock(com.foremen.service.audit.AuditLogDao.class),
                entityManager,
                estimateDao,
                estimateLineDao,
                estimateLineRoomQtyDao,
                roomDao,
                workItemDao,
                /* workPriceDao           */ mock(WorkPriceDao.class),
                workMaterialConsumptionDao,
                workVolumeFormulaDao,
                workPackageOverrideDao,
                offerPackageDao,
                constructionMaterialDao,
                finishingMaterialDao,
                /* constructionTypeDao    */ mock(ConstructionMaterialTypeDao.class),
                /* materialTypeDao        */ mock(MaterialTypeDao.class),
                assortmentPositionPriceDao,
                /* draftGateGuard         */ mock(DraftGateGuard.class),
                estimateRecomputeService,
                new PriceRangeResolver(),
                new FinishingPriceRangeResolver(),
                /* estimateMatrixAssembler */ mock(com.foremen.service.estimate.matrix.EstimateMatrixAssembler.class));
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private WorkItemEntity work(Long id, Set<Long> attachedTypeIds) {
        WorkItemEntity work = new WorkItemEntity();
        work.setId(id);
        work.setNameRU("w-ru");
        work.setNamePL("w-pl");

        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setId(1L);
        unit.setCode(UNIT_CODE);
        work.setUnit(unit);

        Set<RoomTypeEntity> attachment = new HashSet<>();
        for (Long typeId : attachedTypeIds) {
            attachment.add(roomType(typeId));
        }
        work.setRoomTypes(attachment);
        return work;
    }

    /** A default formula whose Volume is exactly {@code room.floorArea}, giving a predictable plan value. */
    private WorkVolumeFormulaEntity floorAreaFormula(WorkItemEntity work) {
        WorkVolumeFormulaEntity formula = new WorkVolumeFormulaEntity();
        formula.setId(100L);
        formula.setWorkItem(work);
        formula.setSourceText("floorArea");
        formula.setParsedAst(new FormulaAst.Var("floorArea"));
        return formula;
    }

    private RoomTypeEntity roomType(Long id) {
        RoomTypeEntity type = new RoomTypeEntity();
        type.setId(id);
        type.setCode("RT-" + id);
        type.setNameRU("rt-ru-" + id);
        type.setNamePL("rt-pl-" + id);
        return type;
    }

    private List<RoomEntity> buildRooms(List<RoomSpec> specs) {
        List<RoomEntity> rooms = new ArrayList<>();
        long roomId = 1000L;
        for (RoomSpec spec : specs) {
            RoomEntity room = new RoomEntity();
            room.setId(roomId++);
            room.setRoomType(roomType(spec.typeId));
            room.setFloorArea(spec.floorArea);
            rooms.add(room);
        }
        return rooms;
    }

    private RoomEntity roomById(List<RoomEntity> rooms, Long id) {
        for (RoomEntity room : rooms) {
            if (room.getId().equals(id)) {
                return room;
            }
        }
        return null;
    }

    /**
     * An estimate carrying a single line for {@code work} pre-assigned to the given room ids (each
     * with an arbitrary existing Volume distinct from the formula value, so "not overriding" is
     * meaningful). An empty {@code assignedRoomIds} yields an estimate with no lines.
     */
    private EstimateEntity estimateWithAssignments(
            WorkItemEntity work, List<RoomEntity> rooms, Set<Long> assignedRoomIds) {
        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(500L);
        if (assignedRoomIds.isEmpty()) {
            return estimate;
        }
        EstimateLineEntity line = new EstimateLineEntity();
        line.setId(600L);
        line.setEstimate(estimate);
        line.setWorkItem(work);
        List<EstimateLineRoomQtyEntity> roomQtys = new ArrayList<>();
        for (RoomEntity room : rooms) {
            if (assignedRoomIds.contains(room.getId())) {
                EstimateLineRoomQtyEntity rq = new EstimateLineRoomQtyEntity();
                rq.setLine(line);
                rq.setRoom(room);
                rq.setQuantity(new BigDecimal("9999.0000")); // existing Volume, must not be overridden
                roomQtys.add(rq);
            }
        }
        line.setRoomQtys(roomQtys);
        estimate.setLines(new ArrayList<>(List.of(line)));
        return estimate;
    }

    /** A generated room: its room type id (small pool so attachment matches collide) + a Volume. */
    private record RoomSpec(Long typeId, BigDecimal floorArea) {
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<List<RoomSpec>> rooms() {
        Arbitrary<Long> typeIds = Arbitraries.longs().between(1L, 4L);
        Arbitrary<BigDecimal> floorArea = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("500.00"))
                .ofScale(2);
        Arbitrary<RoomSpec> spec = Combinators.combine(typeIds, floorArea).as(RoomSpec::new);
        return spec.list().ofMinSize(0).ofMaxSize(12);
    }

    /** The work's Room_Type_Attachment: empty (⇒ all rooms) or a subset of the room-type pool. */
    @Provide
    Arbitrary<Set<Long>> attachment() {
        return Arbitraries.longs().between(1L, 4L).set().ofMinSize(0).ofMaxSize(4);
    }

    /** Indexes (into the generated room list) of cells already assigned to the work, for layering. */
    @Provide
    Arbitrary<Set<Long>> existing() {
        return Arbitraries.longs().between(0L, 11L).set().ofMinSize(0).ofMaxSize(6);
    }
}
