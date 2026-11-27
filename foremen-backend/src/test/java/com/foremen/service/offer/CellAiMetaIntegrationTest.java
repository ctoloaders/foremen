package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.service.OfferService;
import com.foremen.service.estimate.CellAiMeta;
import com.foremen.service.estimate.CellMetaInput;
import com.foremen.service.estimate.EstimateAssignmentService;
import com.foremen.service.estimate.EstimateAssignmentService.EditKind;
import com.foremen.service.estimate.EstimateAssignmentService.StagedEdit;
import com.foremen.service.estimate.EstimateScratchService;
import com.foremen.service.estimate.EstimateScratchService.Seed;
import com.foremen.service.estimate.matrix.CellDto;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;

/**
 * FOR-05-04 task 3.11 (Requirements 21.2, 21.3, 21.7, 21.9, 12.4, 12.6): cell AI meta through the scratch
 * overlay, the version 3 snapshot, the restore and the estimate matrix, against Testcontainers.
 * Repeatability: per-run ids, rows removed by {@link OfferExplorationSupport#cleanUpExploration()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class CellAiMetaIntegrationTest extends OfferExplorationSupport {

    private static final BigDecimal FLOOR = new BigDecimal("12.40");

    @Autowired
    private EstimateScratchService scratch;
    @Autowired
    private OfferService offerService;
    @Autowired
    private OfferRestoreService restoreService;
    @Autowired
    private EstimateAssignmentService assignmentService;

    /** Fixture whose work is measured in m2, so the unit fallback gives the room's floor area (12.40). */
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

    private String workCode(Fx f) {
        return tx().execute(s -> entityManager.find(WorkItemEntity.class, f.workItemId).getCode());
    }

    private CellMetaInput input(Fx f, String rationale, String volumeReason, boolean override) {
        return new CellMetaInput(workCode(f), f.roomId, "JOB", null, rationale, volumeReason, "ASSUMED",
                new BigDecimal("0.72"), "plan.pdf", 3, null, override);
    }

    private static StagedEdit setQuantity(Fx f, BigDecimal qty) {
        return new StagedEdit(EditKind.SET_QUANTITY, f.workItemId, f.roomId, null, null, null, null, null,
                null, qty);
    }

    private static OfferSnapshot.Cell onlyCell(OfferSnapshot s) {
        return s.groups().get(0).rows().get(0).cells().get(0);
    }

    private EstimateLineRoomQtyEntity liveCell(Fx f) {
        return tx().execute(s -> entityManager.createQuery(
                "select q from EstimateLineRoomQtyEntity q where q.line.estimate.id = :e", EstimateLineRoomQtyEntity.class)
                .setParameter("e", f.estimateId).getSingleResult());
    }

    private static CellDto matrixCell(EstimateMatrixDto m, Long workItemId, Long roomId) {
        return m.groups().stream().flatMap(g -> g.rows().stream())
                .filter(r -> r.workItemId().equals(workItemId))
                .flatMap(r -> r.cells().stream())
                .filter(c -> c.roomId().equals(roomId))
                .findFirst().orElseThrow();
    }

    /** An offer of the project whose snapshot is replaced by {@code snapshot}. */
    private Long offerWith(Fx f, OfferSnapshot snapshot) {
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        OfferEntity offer = offerService.prepareOffer(f.projectId, "AI " + runId);
        tx().executeWithoutResult(s -> entityManager.find(OfferEntity.class, offer.getId()).setSnapshot(snapshot));
        return offer.getId();
    }

    @Test
    @DisplayName("overridden AI cell reports the formula volume; v3 snapshot round-trips ai; restore keeps the override and ai; manual edit clears it")
    void overrideFactsRoundTripRestoreAndClearing() {
        Fx f = m2Fixture("AI1");
        authenticateAdmin();

        OfferSnapshot snapshot = scratch.compute(f.projectId, Seed.LIVE,
                List.of(StagedEdit.assign(f.workItemId, f.roomId, null), setQuantity(f, new BigDecimal("14.00"))),
                List.of(input(f, "Bathroom walls are tiled (sheet 3).", "Niche of 1.6 m2 is tiled.", true)));

        assertThat(snapshot.version()).isEqualTo(3);
        OfferSnapshot.Cell cell = onlyCell(snapshot);
        assertThat(cell.quantity()).isEqualByComparingTo("14.00");
        assertThat(cell.volumeOverridden()).isTrue();
        CellAiMeta ai = cell.ai();
        assertThat(ai).isNotNull();
        assertThat(ai.origin()).isEqualTo("JOB");
        assertThat(ai.rationale()).isEqualTo("Bathroom walls are tiled (sheet 3).");
        assertThat(ai.source().file()).isEqualTo("plan.pdf");
        assertThat(ai.volume().source()).isEqualTo("MODEL_OVERRIDE");
        assertThat(ai.volume().formulaVolume()).isEqualByComparingTo(FLOOR);
        assertThat(ai.volume().finalVolume()).isEqualByComparingTo("14.00");
        assertThat(ai.volume().correction().delta()).isEqualByComparingTo("1.60");
        assertThat(ai.volume().correction().pct()).isEqualByComparingTo("12.9");
        assertThat(ai.volume().adjustmentReason()).isEqualTo("Niche of 1.6 m2 is tiled.");
        assertThat(ai.volume().reasonCode()).isNull();
        assertThat(cell.fallbackUsed()).isTrue();

        // Round trip through the offers.snapshot JSONB column keeps ai and the override flag.
        Long offerId = offerWith(f, snapshot);
        OfferSnapshot stored = tx().execute(s -> entityManager.find(OfferEntity.class, offerId).getSnapshot());
        assertThat(onlyCell(stored).ai()).isEqualTo(ai);
        assertThat(onlyCell(stored).volumeOverridden()).isTrue();

        // Staff snapshot read exposes the explanation; the client-shaped view does not carry it.
        OfferSnapshotViews.StaffSnapshotView staff = offerSnapshotStaffView(offerId);
        assertThat(staff.aiCells()).singleElement().satisfies(c -> {
            assertThat(c.workItemId()).isEqualTo(f.workItemId);
            assertThat(c.ai()).isEqualTo(ai);
        });

        // Restore keeps the override flag and copies ai into estimate_line_room_qty.ai_meta.
        restoreService.restore(offerId);
        EstimateLineRoomQtyEntity restored = liveCell(f);
        assertThat(restored.isVolumeOverridden()).isTrue();
        assertThat(restored.getQuantity()).isEqualByComparingTo("14.00");
        assertThat(restored.getAiMeta()).isEqualTo(ai);

        // The estimate matrix maps it to CellDto.ai.
        CellDto matrix = matrixCell(assignmentService.getMatrix(f.projectId, true), f.workItemId, f.roomId);
        assertThat(matrix.ai()).isEqualTo(ai);
        assertThat(matrix.volumeOverridden()).isTrue();

        // A staged manual edit shows the cleared marker in the preview, and Save clears it.
        CellDto preview = matrixCell(assignmentService.previewStagedEdits(
                f.projectId, List.of(setQuantity(f, new BigDecimal("15.00"))), true), f.workItemId, f.roomId);
        assertThat(preview.ai()).isNull();
        assertThat(liveCell(f).getAiMeta()).isEqualTo(ai);
        assertThat(onlyCell(scratch.compute(f.projectId, Seed.LIVE,
                List.of(setQuantity(f, new BigDecimal("16.00"))), List.of())).ai())
                .as("an AI replay keeps the marker").isEqualTo(ai);
        assignmentService.applyAssignments(f.projectId, List.of(setQuantity(f, new BigDecimal("15.00"))));
        assertThat(liveCell(f).getAiMeta()).isNull();
    }

    @Test
    @DisplayName("a formula cell carries UNIT_FALLBACK facts and no correction; a missing volume reason gives MODEL_VOLUME_DIFFERS")
    void fallbackCellAndMissingReason() {
        Fx f = m2Fixture("AI2");
        authenticateAdmin();

        OfferSnapshot plain = scratch.compute(f.projectId, Seed.LIVE,
                List.of(StagedEdit.assign(f.workItemId, f.roomId, null)),
                List.of(input(f, "Floor is renovated.", null, false)));
        CellAiMeta.Volume v = onlyCell(plain).ai().volume();
        assertThat(onlyCell(plain).volumeOverridden()).isNull();
        assertThat(v.source()).isEqualTo("UNIT_FALLBACK");
        assertThat(v.formulaKey()).isEqualTo("fallback");
        assertThat(v.formulaVolume()).isEqualByComparingTo(FLOOR);
        assertThat(v.finalVolume()).isEqualByComparingTo(FLOOR);
        assertThat(v.correction()).isNull();
        assertThat(v.adjustmentReason()).isNull();

        OfferSnapshot noReason = scratch.compute(f.projectId, Seed.LIVE,
                List.of(StagedEdit.assign(f.workItemId, f.roomId, null), setQuantity(f, new BigDecimal("20.00"))),
                List.of(input(f, null, "  ", true)));
        CellAiMeta ai = onlyCell(noReason).ai();
        assertThat(ai.rationale()).isNull();
        assertThat(ai.volume().adjustmentReason()).isNull();
        assertThat(ai.volume().reasonCode()).isEqualTo("MODEL_VOLUME_DIFFERS");
    }

    @Test
    @DisplayName("a version 2 snapshot (cells without the new fields) still restores, without override or ai")
    void version2SnapshotStillRestores() {
        Fx f = m2Fixture("AI3");
        authenticateAdmin();
        addLine(f, new BigDecimal("10.00"), new BigDecimal("2.00"));
        OfferEntity offer = offerService.prepareOffer(f.projectId, "V2 " + runId);
        tx().executeWithoutResult(s -> {
            OfferEntity o = entityManager.find(OfferEntity.class, offer.getId());
            OfferSnapshot v3 = o.getSnapshot();
            OfferSnapshot.Group g = v3.groups().get(0);
            OfferSnapshot.Row r = g.rows().get(0);
            OfferSnapshot.Row row = new OfferSnapshot.Row(r.workItemId(), r.nameRu(), r.namePl(), r.unitCode(),
                    r.unitNameRu(), r.unitNamePl(), r.quantity(), r.unitPrice(), r.baseUnitPrice(), r.valueNet(),
                    r.baseValueNet(), List.of(new OfferSnapshot.Cell(f.roomId, new BigDecimal("3.00"))),
                    r.unitPriceOverride(), r.comment(), r.workPriceId(), r.lineNo());
            o.setSnapshot(new OfferSnapshot(2, v3.capturedAt(), v3.vatRatePct(), v3.totalNet(), v3.totalVat(),
                    v3.totalGross(), v3.rooms(),
                    List.of(new OfferSnapshot.Group(g.categoryId(), g.nameRu(), g.namePl(), List.of(row)))));
        });

        OfferRestoreService.RestoreResult result = restoreService.restore(offer.getId());

        assertThat(result.restoredLines()).isEqualTo(1);
        EstimateLineRoomQtyEntity cell = liveCell(f);
        assertThat(cell.getQuantity()).isEqualByComparingTo("3.00");
        assertThat(cell.isVolumeOverridden()).isFalse();
        assertThat(cell.getAiMeta()).isNull();
    }

    @Autowired
    private OfferSnapshotService offerSnapshotService;

    private OfferSnapshotViews.StaffSnapshotView offerSnapshotStaffView(Long offerId) {
        return offerSnapshotService.staffSnapshot(offerId, true, false);
    }
}
