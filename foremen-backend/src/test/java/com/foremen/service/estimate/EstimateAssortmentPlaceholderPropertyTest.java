package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

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
import com.foremen.dao.model.AssortmentPositionEntity;
import com.foremen.dao.model.AssortmentPositionPriceEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.dao.model.WorkPackageOverrideEntity;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.EstimateAssignmentService.AssignmentPlan;
import com.foremen.service.estimate.EstimateAssignmentService.CalculatedApply;
import com.foremen.service.estimate.EstimateAssignmentService.MaterialLinePlan;
import com.foremen.service.model.mapper.EstimateLineRoomMaterialServiceMapper;
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
 * Property-based test for the {@code Apply_Package} finishing {@code Assortment_Placeholder} seeding
 * of {@link EstimateAssignmentService} (FOR-05-05, task 5.5).
 *
 * <p>When a package is applied, the read-only calculate core seeds one finishing
 * {@code Assortment_Placeholder} for a work's finishing consumption type <b>exactly when</b> the
 * package carries an assortment position of the matching material type, and seeds <b>no</b>
 * assortment placeholder for a finishing consumption type that has no matching assortment position
 * (R11.6). Each seeded placeholder carries that position's per-package price band
 * ({@code minPrice..maxPrice}).
 *
 * <p>The seeding core ({@code planMaterialLines} / {@code assortmentPricesByType}) is private, so the
 * property drives it through the public {@link EstimateAssignmentService#calculateApplyPackage} entry
 * point over an in-memory catalog graph (mocked DAOs). To keep the assortment-match signal crisp,
 * the finishing-material catalog is empty — a finishing consumption type with no matching assortment
 * position therefore yields a non-assortment line with an {@code EMPTY} range, so an
 * {@code assortment == true} planned line appears iff-and-only-iff the type matched a package
 * assortment position.
 *
 * <p>Feature: for-05-05-bill-of-materials, Property 7: Apply seeds finishing placeholders by
 * material-type match
 *
 * <p><b>Validates: Requirements 11.6</b>
 */
// Feature: for-05-05-bill-of-materials, Property 7: Apply seeds finishing placeholders by material-type match
@Tag("Feature: for-05-05-bill-of-materials, Property 7: Apply seeds finishing placeholders by material-type match")
class EstimateAssortmentPlaceholderPropertyTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long PACKAGE_ID = 42L;
    private static final String PACKAGE_CODE = "norm";
    private static final Long WORK_ID = 100L;
    private static final Long ROOM_ID = 500L;

    /**
     * Property 7: for any set of a work's finishing consumption material types and any set of a
     * package's assortment position material types (each position carrying a per-package price band),
     * applying the package plans a finishing {@code Assortment_Placeholder} for exactly the finishing
     * consumption types whose material type equals a package assortment position's material type — and
     * for no other finishing type — with each placeholder carrying that position's {@code min..max}
     * band.
     *
     * <p>Feature: for-05-05-bill-of-materials, Property 7: Apply seeds finishing placeholders by
     * material-type match
     *
     * <p><b>Validates: Requirements 11.6</b>
     */
    @Property(tries = 100)
    @Tag("Feature: for-05-05-bill-of-materials, Property 7: Apply seeds finishing placeholders by material-type match")
    void applySeedsFinishingPlaceholdersByMaterialTypeMatch(@ForAll("scenarios") Scenario scenario) {

        EstimateAssignmentService service = newService(scenario);

        CalculatedApply result = service.calculateApplyPackage(PROJECT_ID, PACKAGE_CODE);

        // Exactly one assignment plan for the single member work in the single room (layering over an
        // empty estimate; empty room-type attachment attaches to all rooms).
        assertThat(result.assignments()).hasSize(1);
        AssignmentPlan plan = result.assignments().get(0);
        assertThat(plan.workItemId()).isEqualTo(WORK_ID);
        assertThat(plan.roomId()).isEqualTo(ROOM_ID);

        // The expected match set: finishing consumption types that equal a package assortment type.
        Set<Long> expectedPlaceholderTypes = new HashSet<>(scenario.finishingConsumptionTypeIds);
        expectedPlaceholderTypes.retainAll(scenario.assortmentTypeIds);

        // Every planned line is finishing (this scenario seeds only finishing consumptions).
        Set<Long> actualAssortmentTypes = new HashSet<>();
        for (MaterialLinePlan line : plan.materials()) {
            assertThat(line.branch()).isEqualTo(ConsumptionBranch.finishing);
            if (line.assortment()) {
                actualAssortmentTypes.add(line.typeId());
                // The placeholder carries the matched position's per-package band.
                assertThat(scenario.assortmentTypeIds).contains(line.typeId());
                assertThat(line.rangeMin()).isEqualByComparingTo(scenario.bandMin(line.typeId()));
                assertThat(line.rangeMax()).isEqualByComparingTo(scenario.bandMax(line.typeId()));
            } else {
                // A non-assortment finishing line: its type must NOT be a package assortment type
                // (empty finishing catalog -> EMPTY resolver range, so it is never a match).
                assertThat(expectedPlaceholderTypes).doesNotContain(line.typeId());
                assertThat(line.rangeMin()).isNull();
                assertThat(line.rangeMax()).isNull();
            }
        }

        // Seeded assortment placeholders are exactly the matching types — none otherwise (R11.6).
        assertThat(actualAssortmentTypes).isEqualTo(expectedPlaceholderTypes);

        // One planned line per distinct finishing consumption type (keyed per (branch, type)).
        assertThat(plan.materials()).hasSize(scenario.finishingConsumptionTypeIds.size());
    }

    // ------------------------------------------------------------------------------------------
    // Service assembly over the in-memory graph
    // ------------------------------------------------------------------------------------------

    private EstimateAssignmentService newService(Scenario scenario) {
        EstimateLineRoomMaterialDao estimateLineRoomMaterialDao = mock(EstimateLineRoomMaterialDao.class);
        EstimateLineRoomMaterialServiceMapper mapper = mock(EstimateLineRoomMaterialServiceMapper.class);
        ProjectAccessCache projectAccessCache = mock(ProjectAccessCache.class);
        AuditLogDao auditLogDao = mock(AuditLogDao.class);
        EntityManager entityManager = mock(EntityManager.class);
        EstimateDao estimateDao = mock(EstimateDao.class);
        EstimateLineDao estimateLineDao = mock(EstimateLineDao.class);
        EstimateLineRoomQtyDao estimateLineRoomQtyDao = mock(EstimateLineRoomQtyDao.class);
        RoomDao roomDao = mock(RoomDao.class);
        WorkItemDao workItemDao = mock(WorkItemDao.class);
        WorkPriceDao workPriceDao = mock(WorkPriceDao.class);
        WorkMaterialConsumptionDao workMaterialConsumptionDao = mock(WorkMaterialConsumptionDao.class);
        WorkVolumeFormulaDao workVolumeFormulaDao = mock(WorkVolumeFormulaDao.class);
        WorkPackageOverrideDao workPackageOverrideDao = mock(WorkPackageOverrideDao.class);
        OfferPackageDao offerPackageDao = mock(OfferPackageDao.class);
        ConstructionMaterialDao constructionMaterialDao = mock(ConstructionMaterialDao.class);
        FinishingMaterialDao finishingMaterialDao = mock(FinishingMaterialDao.class);
        ConstructionMaterialTypeDao constructionMaterialTypeDao = mock(ConstructionMaterialTypeDao.class);
        MaterialTypeDao materialTypeDao = mock(MaterialTypeDao.class);
        AssortmentPositionPriceDao assortmentPositionPriceDao = mock(AssortmentPositionPriceDao.class);
        DraftGateGuard draftGateGuard = mock(DraftGateGuard.class);
        EstimateRecomputeService estimateRecomputeService = mock(EstimateRecomputeService.class);

        // Real pure resolvers (they are pure over the passed-in material collections).
        PriceRangeResolver priceRangeResolver = new PriceRangeResolver();
        FinishingPriceRangeResolver finishingPriceRangeResolver = new FinishingPriceRangeResolver();
        // Read-model assembler: mocked; this placeholder-seeding calculate test never assembles a matrix.
        com.foremen.service.estimate.matrix.EstimateMatrixAssembler estimateMatrixAssembler =
                mock(com.foremen.service.estimate.matrix.EstimateMatrixAssembler.class);

        OfferPackageEntity offerPackage = scenario.offerPackage;
        WorkItemEntity work = scenario.work;
        RoomEntity room = scenario.room;

        lenient().when(estimateDao.findByProjectId(PROJECT_ID))
                .thenReturn(java.util.Optional.of(scenario.estimate));
        lenient().when(offerPackageDao.findByCode(PACKAGE_CODE)).thenReturn(java.util.Optional.of(offerPackage));
        lenient().when(roomDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(room));

        // The single member work of the package.
        WorkPackageOverrideEntity memberOverride = new WorkPackageOverrideEntity();
        memberOverride.setWorkItem(work);
        memberOverride.setOfferPackage(offerPackage);
        memberOverride.setMember(Boolean.TRUE);
        lenient().when(workPackageOverrideDao.findAllWithWorkItemAndPackage())
                .thenReturn(List.of(memberOverride));
        lenient().when(workPackageOverrideDao.findByWorkItemIdAndOfferPackageId(WORK_ID, PACKAGE_ID))
                .thenReturn(java.util.Optional.of(memberOverride));

        // No default volume formula -> unit fallback (szt -> 1 per room); volume never null.
        lenient().when(workVolumeFormulaDao.findByWorkItemId(WORK_ID)).thenReturn(java.util.Optional.empty());

        // The work's finishing consumptions.
        lenient().when(workMaterialConsumptionDao.findByWorkItemIdIn(any())).thenReturn(scenario.consumptions);

        // Empty finishing catalog: a non-matching finishing type resolves to EMPTY, so an assortment
        // placeholder is the ONLY way a line becomes assortment == true.
        lenient().when(finishingMaterialDao.findByActiveTrueAndRetailNetNotNull()).thenReturn(List.of());
        lenient().when(constructionMaterialDao.findByActiveTrueAndRetailNetNotNull()).thenReturn(List.of());

        // The package's assortment position price rows (indexed by material type inside the service).
        lenient().when(assortmentPositionPriceDao.findAll()).thenReturn(scenario.assortmentPrices);

        lenient().when(workItemDao.findById(anyLong())).thenReturn(java.util.Optional.of(work));

        // The read path now resolves the estimate via the get-or-create collaborator (R1.7).
        com.foremen.service.EstimateService estimateServiceMock = mock(com.foremen.service.EstimateService.class);
        lenient().when(estimateServiceMock.getOrCreateEntityForProject(PROJECT_ID))
                .thenReturn(scenario.estimate);

        return new EstimateAssignmentService(
                estimateLineRoomMaterialDao,
                mapper,
                projectAccessCache,
                auditLogDao,
                entityManager,
                estimateDao,
                estimateLineDao,
                estimateLineRoomQtyDao,
                roomDao,
                workItemDao,
                workPriceDao,
                workMaterialConsumptionDao,
                workVolumeFormulaDao,
                workPackageOverrideDao,
                offerPackageDao,
                constructionMaterialDao,
                finishingMaterialDao,
                constructionMaterialTypeDao,
                materialTypeDao,
                assortmentPositionPriceDao,
                mock(com.foremen.dao.AssortmentPositionDao.class),
                draftGateGuard,
                estimateRecomputeService,
                priceRangeResolver,
                finishingPriceRangeResolver,
                estimateMatrixAssembler,
                estimateServiceMock);
    }

    // ------------------------------------------------------------------------------------------
    // Scenario: a fully-built in-memory graph derived from the generated type-id sets
    // ------------------------------------------------------------------------------------------

    /**
     * One generated scenario: the work's finishing consumption material-type ids, the package's
     * assortment position material-type ids (each with a per-package price band), and the wired
     * in-memory entities the service reads.
     */
    static final class Scenario {
        final Set<Long> finishingConsumptionTypeIds;
        final Set<Long> assortmentTypeIds;
        final WorkItemEntity work;
        final RoomEntity room;
        final OfferPackageEntity offerPackage;
        final EstimateEntity estimate;
        final List<WorkMaterialConsumptionEntity> consumptions;
        final List<AssortmentPositionPriceEntity> assortmentPrices;
        final java.util.Map<Long, BigDecimal> bandMinByType = new java.util.HashMap<>();
        final java.util.Map<Long, BigDecimal> bandMaxByType = new java.util.HashMap<>();

        Scenario(Set<Long> finishingConsumptionTypeIds, Set<Long> assortmentTypeIds) {
            this.finishingConsumptionTypeIds = finishingConsumptionTypeIds;
            this.assortmentTypeIds = assortmentTypeIds;

            MeasurementUnitEntity unit = new MeasurementUnitEntity();
            unit.setId(1L);
            unit.setCode("szt"); // unit fallback -> 1 per room, so a plan is produced

            this.work = new WorkItemEntity();
            work.setId(WORK_ID);
            work.setUnit(unit);
            work.setRoomTypes(new HashSet<>()); // empty attachment -> attaches to all rooms

            RoomTypeEntity roomType = new RoomTypeEntity();
            roomType.setId(9L);

            ProjectEntity project = new ProjectEntity();
            project.setId(PROJECT_ID);

            this.room = new RoomEntity();
            room.setId(ROOM_ID);
            room.setProject(project);
            room.setRoomType(roomType);

            this.offerPackage = new OfferPackageEntity();
            offerPackage.setId(PACKAGE_ID);
            offerPackage.setCode(PACKAGE_CODE);

            this.estimate = new EstimateEntity(); // empty estimate -> no existing assignments

            this.consumptions = new ArrayList<>();
            for (Long typeId : finishingConsumptionTypeIds) {
                MaterialTypeEntity type = new MaterialTypeEntity();
                type.setId(typeId);
                WorkMaterialConsumptionEntity consumption = new WorkMaterialConsumptionEntity();
                consumption.setWorkItem(work);
                consumption.setBranch(ConsumptionBranch.finishing);
                consumption.setFinishingMaterialType(type);
                consumption.setNormQty(new BigDecimal("2.0000"));
                consumptions.add(consumption);
            }

            this.assortmentPrices = new ArrayList<>();
            long seed = 1L;
            for (Long typeId : assortmentTypeIds) {
                MaterialTypeEntity type = new MaterialTypeEntity();
                type.setId(typeId);

                AssortmentPositionEntity position = new AssortmentPositionEntity();
                position.setId(seed);
                position.setMaterialType(type);

                BigDecimal min = new BigDecimal(String.valueOf(10 + seed));
                BigDecimal max = new BigDecimal(String.valueOf(100 + seed));
                bandMinByType.put(typeId, min);
                bandMaxByType.put(typeId, max);

                AssortmentPositionPriceEntity price = new AssortmentPositionPriceEntity();
                price.setId(seed);
                price.setPosition(position);
                price.setOfferPackage(offerPackage);
                price.setMinPrice(min);
                price.setMaxPrice(max);
                assortmentPrices.add(price);
                seed++;
            }
        }

        BigDecimal bandMin(Long typeId) {
            return bandMinByType.get(typeId);
        }

        BigDecimal bandMax(Long typeId) {
            return bandMaxByType.get(typeId);
        }

        @Override
        public String toString() {
            return "Scenario{finishingConsumptionTypeIds=" + finishingConsumptionTypeIds
                    + ", assortmentTypeIds=" + assortmentTypeIds + '}';
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        // Small, overlapping type-id pools so consumption types and assortment types collide
        // (matching), partially overlap, and diverge across runs.
        Arbitrary<Set<Long>> finishingTypes = Arbitraries.longs()
                .between(1L, 6L)
                .set()
                .ofMinSize(0)
                .ofMaxSize(6);
        Arbitrary<Set<Long>> assortmentTypes = Arbitraries.longs()
                .between(3L, 9L)
                .set()
                .ofMinSize(0)
                .ofMaxSize(6);
        return Combinators.combine(finishingTypes, assortmentTypes).as(Scenario::new);
    }
}
