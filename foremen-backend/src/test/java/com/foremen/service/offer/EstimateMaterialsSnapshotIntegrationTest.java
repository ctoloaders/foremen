package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.service.OfferService;
import com.foremen.service.estimate.EstimateAssignmentService;
import com.foremen.service.estimate.EstimateAssignmentService.EditKind;
import com.foremen.service.estimate.EstimateAssignmentService.StagedEdit;
import com.foremen.service.estimate.EstimateScratchService;
import com.foremen.service.estimate.EstimateScratchService.Seed;
import com.foremen.service.estimateai.AiReportBuilder;

/**
 * FOR-05-04 task 3.12 (Requirements 23.1-23.6, 23.8-23.12, 2.1): consumable materials in the offer snapshot,
 * against Testcontainers: store, restore, scratch seed, parity with a manual estimate, round trip with an
 * overridden quantity and a chosen product, chat edit, work without norms, package, version 2 and size.
 * Repeatability: per-run ids (type/product/work codes end with the run id), rows removed by
 * {@link #cleanUpMaterials()} and {@link OfferExplorationSupport#cleanUpExploration()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class EstimateMaterialsSnapshotIntegrationTest extends OfferExplorationSupport {

    private static final BigDecimal FLOOR = new BigDecimal("12.40");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private EstimateScratchService scratch;
    @Autowired
    private OfferService offerService;
    @Autowired
    private OfferRestoreService restoreService;
    @Autowired
    private EstimateAssignmentService assignmentService;

    /** Fixture with the three material types and the chosen-product candidate. */
    private static final class Mx {
        Fx f;
        Long typeA; // PER_UNIT 0.5, has a priced product
        Long typeB; // PER_UNIT 2
        Long typeC; // PER_ROOM 3
        Long productA;
    }

    @AfterEach
    void cleanUpMaterials() {
        tx().executeWithoutResult(s -> {
            String p = "%" + runId + "%";
            entityManager.createQuery("delete from EstimateLineRoomMaterialEntity m where m.roomQty.line.estimate.id in "
                    + "(select e.id from EstimateEntity e where e.project.name like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from WorkMaterialConsumptionEntity c where c.workItem.code like :p")
                    .setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from ConstructionMaterialEntity m where m.type.code like :p")
                    .setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from ConstructionMaterialTypeEntity t where t.code like :p")
                    .setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from OfferPackageEntity o where o.code like :p")
                    .setParameter("p", p).executeUpdate();
        });
    }

    /** m2 work, room with a floor area of 12.40, so the unit fallback gives 12.40. */
    private Fx m2Fixture(String tag) {
        Fx f = emptyEstimateFixture(tag);
        tx().executeWithoutResult(s -> {
            MeasurementUnitEntity m2 = entityManager.createQuery(
                    "select u from MeasurementUnitEntity u where lower(u.code) = 'm2'", MeasurementUnitEntity.class)
                    .getResultList().stream().findFirst().orElseGet(() -> {
                        MeasurementUnitEntity u = new MeasurementUnitEntity();
                        u.setCode("m2");
                        u.setNameRU("m2");
                        u.setNamePL("m2");
                        u.setActive(true);
                        entityManager.persist(u);
                        return u;
                    });
            entityManager.find(WorkItemEntity.class, f.workItemId).setUnit(m2);
            entityManager.find(RoomEntity.class, f.roomId).setFloorArea(FLOOR);
        });
        return f;
    }

    private Mx materialFixture(String tag, boolean withNorms) {
        Mx m = new Mx();
        m.f = m2Fixture(tag);
        tx().executeWithoutResult(s -> {
            MeasurementUnitEntity unit = entityManager.find(MeasurementUnitEntity.class, m.f.unitId);
            CurrencyEntity pln = currencyDao.findByCode("PLN").orElseThrow();
            WorkItemEntity work = entityManager.find(WorkItemEntity.class, m.f.workItemId);
            m.typeA = type("A", tag);
            m.typeB = type("B", tag);
            m.typeC = type("C", tag);
            ConstructionMaterialEntity product = new ConstructionMaterialEntity();
            product.setNameRU("P " + runId);
            product.setNamePL("P " + runId);
            product.setType(entityManager.find(ConstructionMaterialTypeEntity.class, m.typeA));
            product.setUnit(unit);
            product.setCurrency(pln);
            product.setRetailNet(new BigDecimal("10.00"));
            product.setActive(true);
            entityManager.persist(product);
            ConstructionMaterialEntity productB = new ConstructionMaterialEntity();
            productB.setNameRU("PB " + runId);
            productB.setNamePL("PB " + runId);
            productB.setType(entityManager.find(ConstructionMaterialTypeEntity.class, m.typeB));
            productB.setUnit(unit);
            productB.setCurrency(pln);
            productB.setRetailNet(new BigDecimal("4.00"));
            productB.setActive(true);
            entityManager.persist(productB);
            if (withNorms) {
                consumption(work, unit, m.typeA, "0.5", ConsumptionBasis.PER_UNIT);
                consumption(work, unit, m.typeB, "2", ConsumptionBasis.PER_UNIT);
                consumption(work, unit, m.typeC, "3", ConsumptionBasis.PER_ROOM);
            }
            entityManager.flush();
            m.productA = product.getId();
        });
        return m;
    }

    private Long type(String suffix, String tag) {
        ConstructionMaterialTypeEntity t = new ConstructionMaterialTypeEntity();
        t.setCode("MT_" + suffix + "_" + tag + "_" + runId);
        t.setNameRU("T" + suffix);
        t.setNamePL("T" + suffix);
        t.setActive(true);
        entityManager.persist(t);
        entityManager.flush();
        return t.getId();
    }

    private void consumption(WorkItemEntity work, MeasurementUnitEntity unit, Long typeId, String norm,
                             ConsumptionBasis basis) {
        WorkMaterialConsumptionEntity c = new WorkMaterialConsumptionEntity();
        c.setWorkItem(work);
        c.setMaterialUnit(unit);
        c.setBranch(ConsumptionBranch.construction);
        c.setConstructionMaterialType(entityManager.find(ConstructionMaterialTypeEntity.class, typeId));
        c.setNormQty(new BigDecimal(norm));
        c.setConsumptionBasis(basis);
        c.setSourceType("TEST");
        c.setSourceDoc("test");
        c.setSourceRef("test");
        entityManager.persist(c);
    }

    private static StagedEdit setQuantity(Fx f, String qty) {
        return new StagedEdit(EditKind.SET_QUANTITY, f.workItemId, f.roomId, null, null, null, null, null,
                null, new BigDecimal(qty));
    }

    private static StagedEdit setMaterialQty(Fx f, Long typeId, String qty) {
        return new StagedEdit(EditKind.SET_MATERIAL_QUANTITY, f.workItemId, f.roomId, null,
                ConsumptionBranch.construction, typeId, null, null, null, new BigDecimal(qty));
    }

    private static StagedEdit chooseConcrete(Fx f, Long typeId, Long productId) {
        return new StagedEdit(EditKind.CHOOSE_CONCRETE, f.workItemId, f.roomId, null,
                ConsumptionBranch.construction, typeId, null, productId, null, null);
    }

    private static OfferSnapshot.Cell onlyCell(OfferSnapshot s) {
        return s.groups().get(0).rows().get(0).cells().get(0);
    }

    private static OfferSnapshot.MaterialLine line(OfferSnapshot s, Long typeId) {
        return onlyCell(s).materials().stream().filter(m -> typeId.equals(m.typeId())).findFirst().orElseThrow();
    }

    private static String num(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }

    /** Everything the parity requirement compares about the live estimate, one string per row. */
    private List<String> signature(Fx f) {
        return tx().execute(s -> {
            List<String> out = new ArrayList<>();
            for (EstimateLineRoomMaterialEntity m : entityManager.createQuery(
                    "select m from EstimateLineRoomMaterialEntity m where m.roomQty.line.estimate.id = :e",
                    EstimateLineRoomMaterialEntity.class).setParameter("e", f.estimateId).getResultList()) {
                out.add("M|" + m.getRoomQty().getLine().getWorkItem().getId() + "|" + m.getRoomQty().getRoom().getId()
                        + "|cellQty=" + num(m.getRoomQty().getQuantity())
                        + "|cellOverridden=" + m.getRoomQty().isVolumeOverridden()
                        + "|" + m.getBranch() + "|" + m.getConstructionType().getId()
                        + "|norm=" + num(m.getNormQty()) + "|" + m.getConsumptionBasis()
                        + "|overridden=" + m.isQtyOverridden() + "|manual=" + num(m.getManualQty())
                        + "|need=" + num(EstimateAssignmentService.materialNeed(m))
                        + "|range=" + num(m.getRangeMin()) + ".." + num(m.getRangeMax())
                        + "|net=" + num(m.getConcreteNet())
                        + "|product=" + (m.getConcreteConstructionMaterial() != null
                                ? m.getConcreteConstructionMaterial().getId() : null)
                        + "|pkg=" + m.isAppliedFromPackage());
            }
            java.util.Collections.sort(out);
            EstimateEntity e = entityManager.find(EstimateEntity.class, f.estimateId);
            out.add("TOTAL|" + num(e.getTotalNet()) + "|" + num(e.getTotalGross()) + "|lines=" + e.getLines().size());
            return out;
        });
    }

    private OfferEntity prepare(Fx f, String name) {
        return offerService.prepareOffer(f.projectId, name + " " + runId);
    }

    private void setSnapshot(Long offerId, OfferSnapshot snapshot) {
        tx().executeWithoutResult(s -> entityManager.find(OfferEntity.class, offerId).setSnapshot(snapshot));
    }

    private OfferSnapshot storedSnapshot(Long offerId) {
        return tx().execute(s -> entityManager.find(OfferEntity.class, offerId).getSnapshot());
    }

    @Test
    @DisplayName("round trip estimate -> snapshot -> restore keeps an overridden quantity, a chosen product and the norms; size stays small")
    void roundTripKeepsOverrideAndChosenProduct() throws Exception {
        Mx m = materialFixture("MR1", true);
        authenticateAdmin();
        assignmentService.applyAssignments(m.f.projectId, List.of(
                StagedEdit.assign(m.f.workItemId, m.f.roomId, null),
                setMaterialQty(m.f, m.typeB, "7.5"),
                chooseConcrete(m.f, m.typeA, m.productA)));
        List<String> before = signature(m.f);
        assertThat(before).anyMatch(r -> r.contains("overridden=true") && r.contains("manual=7.5"));
        assertThat(before).anyMatch(r -> r.contains("product=" + m.productA));

        OfferEntity offer = prepare(m.f, "MR1");
        OfferSnapshot stored = storedSnapshot(offer.getId());
        assertThat(stored.version()).isEqualTo(3);
        assertThat(onlyCell(stored).materials()).hasSize(3);
        OfferSnapshot.MaterialLine a = line(stored, m.typeA);
        assertThat(a.concreteConstructionMaterialId()).isEqualTo(m.productA);
        assertThat(a.concreteNet()).isEqualByComparingTo("10.00");
        assertThat(a.quantity()).isEqualByComparingTo("6.2000");
        assertThat(a.consumptionBasis()).isEqualTo("PER_UNIT");
        OfferSnapshot.MaterialLine b = line(stored, m.typeB);
        assertThat(b.overridden()).isTrue();
        assertThat(b.manualQty()).isEqualByComparingTo("7.5");
        assertThat(b.quantity()).isEqualByComparingTo("7.5");
        assertThat(b.normQty()).isEqualByComparingTo("2");
        assertThat(b.rangeMin()).isEqualByComparingTo("4.00");
        OfferSnapshot.MaterialLine c = line(stored, m.typeC);
        assertThat(c.consumptionBasis()).isEqualTo("PER_ROOM");
        assertThat(c.quantity()).isEqualByComparingTo("3");
        assertThat(c.qtyOverridden()).isNull();
        // size sanity: a material line is a few hundred bytes of JSON, flags only when set
        assertThat(JSON.writeValueAsString(c).length()).isLessThan(400);
        assertThat(JSON.writeValueAsString(onlyCell(stored).materials()).length()).isLessThan(1200);

        // wipe the estimate, then restore from the snapshot: identical, field by field
        assignmentService.applyAssignments(m.f.projectId, List.of(StagedEdit.unassign(m.f.workItemId, m.f.roomId)));
        assertThat(signature(m.f)).hasSize(1);
        OfferRestoreService.RestoreResult result = restoreService.restore(offer.getId());
        assertThat(result.skippedMaterials()).isZero();
        assertThat(signature(m.f)).isEqualTo(before);
        // the scratch seeded from the snapshot without edits is the identity for materials
        OfferSnapshot again = scratch.compute(m.f.projectId, Seed.snapshot(offer.getId()), List.of(), List.of());
        assertThat(onlyCell(again).materials()).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(onlyCell(stored).materials());
    }

    @Test
    @DisplayName("parity: the AI snapshot restored equals the estimate built by hand with the same works, volumes and overrides")
    void parityBetweenManualEstimateAndAiSnapshotRestore() {
        Mx m = materialFixture("MP1", true);
        authenticateAdmin();
        List<StagedEdit> edits = List.of(
                StagedEdit.assign(m.f.workItemId, m.f.roomId, null),
                setQuantity(m.f, "14.00"),
                setMaterialQty(m.f, m.typeB, "3"),
                chooseConcrete(m.f, m.typeA, m.productA));
        // the AI path: same edits replayed on the (still empty) live estimate in a scratch (nothing persists)
        List<String> before = signature(m.f);
        OfferSnapshot ai = scratch.compute(m.f.projectId, Seed.LIVE, edits, List.of());
        assertThat(signature(m.f)).as("scratch persists nothing").isEqualTo(before);
        assignmentService.applyAssignments(m.f.projectId, edits);
        List<String> manual = signature(m.f);
        OfferEntity offer = prepare(m.f, "MP1");
        assertThat(onlyCell(ai).materials()).hasSize(3);
        assertThat(ai.version()).isEqualTo(3);

        setSnapshot(offer.getId(), ai);
        assignmentService.applyAssignments(m.f.projectId, List.of(StagedEdit.unassign(m.f.workItemId, m.f.roomId)));
        restoreService.restore(offer.getId());
        assertThat(signature(m.f)).isEqualTo(manual);
    }

    @Test
    @DisplayName("chat edit: overridden quantities and chosen products survive, non-overridden lines follow the new volume")
    void chatEditKeepsOverridesAndFollowsVolume() {
        Mx m = materialFixture("MC1", true);
        authenticateAdmin();
        assignmentService.applyAssignments(m.f.projectId, List.of(
                StagedEdit.assign(m.f.workItemId, m.f.roomId, null),
                setMaterialQty(m.f, m.typeB, "3"),
                chooseConcrete(m.f, m.typeA, m.productA)));
        OfferEntity offer = prepare(m.f, "MC1");

        OfferSnapshot edited = scratch.compute(m.f.projectId, Seed.snapshot(offer.getId()),
                List.of(setQuantity(m.f, "20.00")), List.of());
        assertThat(onlyCell(edited).quantity()).isEqualByComparingTo("20.00");
        assertThat(onlyCell(edited).volumeOverridden()).isTrue();
        OfferSnapshot.MaterialLine a = line(edited, m.typeA);
        assertThat(a.quantity()).as("0.5 per unit follows the volume").isEqualByComparingTo("10");
        assertThat(a.concreteConstructionMaterialId()).as("chosen product kept").isEqualTo(m.productA);
        OfferSnapshot.MaterialLine b = line(edited, m.typeB);
        assertThat(b.overridden()).isTrue();
        assertThat(b.manualQty()).isEqualByComparingTo("3");
        assertThat(b.quantity()).as("the override does not follow the volume").isEqualByComparingTo("3");
        assertThat(line(edited, m.typeC).quantity()).as("per room is independent of the volume").isEqualByComparingTo("3");

        // a new ASSIGN of a work seeds from the norms; an UNASSIGN removes the cell with its materials
        OfferSnapshot removed = scratch.compute(m.f.projectId, Seed.snapshot(offer.getId()),
                List.of(StagedEdit.unassign(m.f.workItemId, m.f.roomId)), List.of());
        assertThat(removed.groups()).isEmpty();
    }

    @Test
    @DisplayName("a work without norms has no material lines, no warning and is listed in the report summary")
    @SuppressWarnings("unchecked")
    void workWithoutNorms() {
        Mx m = materialFixture("MN1", false);
        authenticateAdmin();
        OfferSnapshot snapshot = scratch.compute(m.f.projectId, Seed.LIVE,
                List.of(StagedEdit.assign(m.f.workItemId, m.f.roomId, null)), List.of());
        assertThat(onlyCell(snapshot).materials()).isNotNull().isEmpty();
        Map<String, Object> report = AiReportBuilder.build(null, snapshot, null, List.of());
        Map<String, Object> materials = (Map<String, Object>) ((Map<String, Object>) report.get("summary")).get("materials");
        assertThat(materials).containsEntry("lines", 0).containsEntry("cellsWithMaterials", 0)
                .containsEntry("overriddenLines", 0);
        assertThat((List<String>) materials.get("worksWithoutNorms")).containsExactly(String.valueOf(m.f.workItemId));
        assertThat((List<Map<String, Object>>) report.get("warnings")).extracting(w -> w.get("code"))
                .doesNotContain("MATERIAL_PRICE_RANGE_MISSING");
        // restore creates no materials and no error
        OfferEntity offer = prepare(m.f, "MN1");
        setSnapshot(offer.getId(), snapshot);
        OfferRestoreService.RestoreResult result = restoreService.restore(offer.getId());
        assertThat(result.restoredLines()).isEqualTo(1);
        assertThat(result.skippedMaterials()).isZero();
        assertThat(signature(m.f)).hasSize(1);
    }

    @Test
    @DisplayName("report: a norm whose type has no price range gets MATERIAL_PRICE_RANGE_MISSING; the package start option is recorded")
    @SuppressWarnings("unchecked")
    void packageChoiceAndMissingRange() {
        Mx m = materialFixture("MK1", true);
        String code = "PK_" + runId;
        tx().executeWithoutResult(s -> {
            OfferPackageEntity pkg = new OfferPackageEntity();
            pkg.setCode(code);
            pkg.setOrderNo(1);
            pkg.setNameRU("Pkg");
            pkg.setNamePL("Pkg");
            pkg.setActive(true);
            entityManager.persist(pkg);
        });
        authenticateAdmin();
        // the estimate's applied package is used by the scratch and recorded in the snapshot
        tx().executeWithoutResult(s ->
                entityManager.find(EstimateEntity.class, m.f.estimateId).setAppliedPackageCode(code));
        OfferSnapshot snapshot = scratch.compute(m.f.projectId, Seed.LIVE,
                List.of(StagedEdit.assign(m.f.workItemId, m.f.roomId, null)), List.of());
        assertThat(snapshot.appliedPackageCode()).isEqualTo(code);
        assertThat(onlyCell(snapshot).materials()).hasSize(3);
        // type C has no priced product: its line is an unpriced placeholder and the report warns
        OfferSnapshot.MaterialLine c = line(snapshot, m.typeC);
        assertThat(c.rangeMin()).isNull();
        assertThat(c.rangeMax()).isNull();
        Map<String, Object> report = AiReportBuilder.build(null, snapshot, null, List.of());
        List<Map<String, Object>> warnings = (List<Map<String, Object>>) report.get("warnings");
        assertThat(warnings).extracting(w -> ((Map<String, Object>) w.get("params")).get("materialTypeId"))
                .containsExactly(m.typeC);
    }

    @Test
    @DisplayName("restore: a gone type is skipped, a gone chosen product becomes a placeholder; both are counted; version 2 restores without materials")
    void restoreSkipsGoneTypesAndVersion2HasNoMaterials() {
        Mx m = materialFixture("MS1", true);
        authenticateAdmin();
        assignmentService.applyAssignments(m.f.projectId, List.of(
                StagedEdit.assign(m.f.workItemId, m.f.roomId, null),
                chooseConcrete(m.f, m.typeA, m.productA)));
        OfferEntity offer = prepare(m.f, "MS1");
        OfferSnapshot stored = storedSnapshot(offer.getId());
        OfferSnapshot.Cell cell = onlyCell(stored);
        List<OfferSnapshot.MaterialLine> lines = new ArrayList<>();
        for (OfferSnapshot.MaterialLine l : cell.materials()) {
            if (m.typeA.equals(l.typeId())) {
                lines.add(withProduct(l, 987654321L)); // product gone
            } else if (m.typeB.equals(l.typeId())) {
                lines.add(withType(l, 987654322L)); // type gone
            } else {
                lines.add(l);
            }
        }
        setSnapshot(offer.getId(), withMaterials(stored, lines));
        OfferRestoreService.RestoreResult result = restoreService.restore(offer.getId());
        assertThat(result.skippedMaterials()).isEqualTo(2);
        List<String> rows = signature(m.f);
        assertThat(rows).filteredOn(r -> r.startsWith("M|")).hasSize(2);
        assertThat(rows).filteredOn(r -> r.contains("|norm=0.5|")).singleElement()
                .satisfies(r -> assertThat(r).contains("net=-").contains("product=null"));

        // a version 2 snapshot restores without any material line
        setSnapshot(offer.getId(), withMaterials(stored, null));
        OfferRestoreService.RestoreResult v2 = restoreService.restore(offer.getId());
        assertThat(v2.skippedMaterials()).isZero();
        assertThat(signature(m.f)).filteredOn(r -> r.startsWith("M|")).isEmpty();
    }

    private static OfferSnapshot.MaterialLine withProduct(OfferSnapshot.MaterialLine l, Long productId) {
        return new OfferSnapshot.MaterialLine(l.branch(), l.constructionTypeId(), l.finishingTypeId(), l.normQty(),
                l.consumptionBasis(), l.rangeMin(), l.rangeMax(), l.qtyOverridden(), l.manualQty(), l.quantity(),
                productId, l.concreteFinishingMaterialId(), l.concreteNet(), l.appliedFromPackage(),
                l.sourceConstructionMaterialId(), l.sourceFinishingMaterialId());
    }

    private static OfferSnapshot.MaterialLine withType(OfferSnapshot.MaterialLine l, Long typeId) {
        return new OfferSnapshot.MaterialLine(l.branch(), typeId, l.finishingTypeId(), l.normQty(),
                l.consumptionBasis(), l.rangeMin(), l.rangeMax(), l.qtyOverridden(), l.manualQty(), l.quantity(),
                l.concreteConstructionMaterialId(), l.concreteFinishingMaterialId(), l.concreteNet(),
                l.appliedFromPackage(), l.sourceConstructionMaterialId(), l.sourceFinishingMaterialId());
    }

    /** The snapshot with the single cell's materials replaced; {@code null} makes it a version 2 snapshot. */
    private static OfferSnapshot withMaterials(OfferSnapshot s, List<OfferSnapshot.MaterialLine> materials) {
        OfferSnapshot.Group g = s.groups().get(0);
        OfferSnapshot.Row r = g.rows().get(0);
        OfferSnapshot.Cell c = r.cells().get(0);
        OfferSnapshot.Cell cell = new OfferSnapshot.Cell(c.roomId(), c.quantity(), c.volumeOverridden(),
                c.formulaKey(), c.fallbackUsed(), c.ai(), materials);
        OfferSnapshot.Row row = new OfferSnapshot.Row(r.workItemId(), r.nameRu(), r.namePl(), r.unitCode(),
                r.unitNameRu(), r.unitNamePl(), r.quantity(), r.unitPrice(), r.baseUnitPrice(), r.valueNet(),
                r.baseValueNet(), List.of(cell), r.unitPriceOverride(), r.comment(), r.workPriceId(), r.lineNo());
        return new OfferSnapshot(materials == null ? 2 : 3, s.capturedAt(), s.vatRatePct(), s.totalNet(),
                s.totalVat(), s.totalGross(), s.rooms(),
                List.of(new OfferSnapshot.Group(g.categoryId(), g.nameRu(), g.namePl(), List.of(row))));
    }
}
