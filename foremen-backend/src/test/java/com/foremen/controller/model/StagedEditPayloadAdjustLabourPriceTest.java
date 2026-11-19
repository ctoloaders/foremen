package com.foremen.controller.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.collab.EstimateCollabService.EntryBody;
import com.foremen.controller.model.ApplyAssignmentsRequest.StagedEditPayload;
import com.foremen.service.estimate.EstimateAssignmentService.EditKind;
import com.foremen.service.estimate.EstimateAssignmentService.StagedEdit;
import com.foremen.service.estimate.LabourPriceAdjustmentScope;

/**
 * FOR-05-05c: {@link StagedEditPayload} carries the {@code ADJUST_LABOUR_PRICE} fields
 * ({@code scope}, {@code targetId}, {@code pct}) through the compat constructor, {@code toStagedEdit()}
 * and the JSON used for the shared collab draft (Redis HASH value = JSON of {@link EntryBody}).
 *
 * <p>The mapper mirrors the one in {@code EstimateCollabService}.
 */
class StagedEditPayloadAdjustLabourPriceTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static StagedEditPayload adjustment(LabourPriceAdjustmentScope scope, Long targetId, String pct) {
        return new StagedEditPayload(EditKind.ADJUST_LABOUR_PRICE, null, null, null, null, null, null, null,
                null, null, scope, targetId, pct == null ? null : new BigDecimal(pct));
    }

    @Test
    void compatConstructor_leavesAdjustmentFieldsNull() {
        StagedEditPayload p = new StagedEditPayload(EditKind.ASSIGN, 1L, 2L, null, null, null, null, null,
                "PKG", null);

        assertNull(p.scope());
        assertNull(p.targetId());
        assertNull(p.pct());
        assertEquals(EditKind.ASSIGN, p.kind());
        assertEquals("PKG", p.packageCode());
    }

    @Test
    void toStagedEdit_mapsAdjustmentFields() {
        StagedEdit e = adjustment(LabourPriceAdjustmentScope.WORK_CATEGORY, 7L, "-12.50").toStagedEdit();

        assertEquals(EditKind.ADJUST_LABOUR_PRICE, e.kind());
        assertEquals(LabourPriceAdjustmentScope.WORK_CATEGORY, e.scope());
        assertEquals(7L, e.targetId());
        assertEquals(new BigDecimal("-12.50"), e.pct());
        assertNull(e.workItemId());
    }

    @Test
    void toStagedEdit_forLegacyPayload_hasNullAdjustmentFields() {
        StagedEdit e = new StagedEditPayload(EditKind.SET_QUANTITY, 1L, 2L, null, null, null, null, null,
                null, new BigDecimal("3.5")).toStagedEdit();

        assertNull(e.scope());
        assertNull(e.targetId());
        assertNull(e.pct());
        assertEquals(new BigDecimal("3.5"), e.quantity());
    }

    @Test
    void jsonRoundTrip_keepsAdjustmentFields_forEveryScope() throws Exception {
        for (LabourPriceAdjustmentScope scope : LabourPriceAdjustmentScope.values()) {
            Long target = scope == LabourPriceAdjustmentScope.PROJECT ? null : 42L;
            StagedEditPayload original = adjustment(scope, target, "10.25");

            StagedEditPayload copy = mapper.readValue(mapper.writeValueAsString(original), StagedEditPayload.class);

            assertEquals(original, copy);
            assertEquals(scope, copy.scope());
            assertEquals(target, copy.targetId());
            assertEquals(0, new BigDecimal("10.25").compareTo(copy.pct()));
            assertEquals(original.toStagedEdit(), copy.toStagedEdit());
        }
    }

    @Test
    void collabDraftEntryBody_roundTrip_keepsAdjustmentPayload() throws Exception {
        StagedEditPayload payload = adjustment(LabourPriceAdjustmentScope.WORK_ITEM, 99L, "-20");
        EntryBody body = new EntryBody("s-1", 5L, "Ann", 3, payload);

        EntryBody copy = mapper.readValue(mapper.writeValueAsString(body), EntryBody.class);

        assertEquals(body, copy);
        assertEquals(EditKind.ADJUST_LABOUR_PRICE, copy.payload().kind());
        assertEquals(LabourPriceAdjustmentScope.WORK_ITEM, copy.payload().scope());
        assertEquals(99L, copy.payload().targetId());
        assertEquals(0, new BigDecimal("-20").compareTo(copy.payload().pct()));
    }

    @Test
    void legacyJsonWithoutAdjustmentFields_deserializesWithNulls() throws Exception {
        String legacy = "{\"kind\":\"ASSIGN\",\"workItemId\":1,\"roomId\":2,\"roomQtyId\":null,"
                + "\"branch\":null,\"typeId\":null,\"materialLineId\":null,\"materialId\":null,"
                + "\"packageCode\":\"PKG\",\"quantity\":null}";

        StagedEditPayload p = mapper.readValue(legacy, StagedEditPayload.class);

        assertEquals(EditKind.ASSIGN, p.kind());
        assertEquals(1L, p.workItemId());
        assertNull(p.scope());
        assertNull(p.targetId());
        assertNull(p.pct());

        String legacyEntry = "{\"stagedId\":\"s-2\",\"authorUserId\":5,\"authorName\":\"Ann\",\"colorIndex\":1,"
                + "\"payload\":" + legacy + "}";
        EntryBody entry = mapper.readValue(legacyEntry, EntryBody.class);
        assertNull(entry.payload().scope());
        assertNull(entry.payload().pct());
        assertNull(entry.payload().toStagedEdit().targetId());
    }
}
