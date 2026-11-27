package com.foremen.service.estimateai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.service.estimateai.PlanCatalog.CatalogWork;
import com.foremen.service.estimateai.PlanCatalog.RoomFacts;

class PlanResolverTest {

    private final ObjectMapper om = new ObjectMapper();

    private final Map<String, CatalogWork> catalog = Map.of(
            "05.01", new CatalogWork(1L, "05.01", "Plaster", true, new BigDecimal("30"), false, Set.of()),
            "05.02", new CatalogWork(2L, "05.02", "Plaster alt", true, new BigDecimal("40"), false, Set.of(99L)),
            "09.01", new CatalogWork(3L, "09.01", "Free", true, BigDecimal.ZERO, false, Set.of()),
            "13.01", new CatalogWork(4L, "13.01", "Coordination", true, new BigDecimal("2"), true, Set.of()),
            "99.99", new CatalogWork(5L, "99.99", "Old", false, new BigDecimal("5"), false, Set.of()));

    private final List<RoomFacts> rooms = List.of(
            new RoomFacts(10L, 1L, new BigDecimal("10"), new BigDecimal("30")),
            new RoomFacts(11L, 1L, new BigDecimal("8"), new BigDecimal("24")));

    private EstimatePlan resolve(String json) throws Exception {
        return PlanResolver.resolve(om.readTree(json), rooms, catalog);
    }

    private static boolean has(EstimatePlan p, String code) {
        return p.warnings().stream().anyMatch(w -> w.code().equals(code));
    }

    @Test
    void unknownCodeNoPriceAndPercentageGoToUnpriced() throws Exception {
        EstimatePlan p = resolve("""
            {"works":[
              {"workCode":"nope","roomIds":[10],"volumes":[{"roomId":10,"value":3,"unit":"m2"}]},
              {"workCode":"09.01","roomIds":[10]},
              {"workCode":"13.01","roomIds":[10]},
              {"workCode":"99.99","roomIds":[10]}]}""");
        assertThat(p.works()).isEmpty();
        assertThat(p.unpriced()).extracting(Unpriced::reason)
                .containsExactly("UNKNOWN_CODE", "NO_PRICE", "UNSUPPORTED_UNIT", "UNKNOWN_CODE");
        assertThat(p.unpriced().get(0).estimatedVolume()).isEqualByComparingTo("3");
    }

    @Test
    void negativeVolumeIsDroppedAndDuplicatesMergedLastWins() throws Exception {
        EstimatePlan p = resolve("""
            {"works":[
              {"workCode":"05.01","roomIds":[10],"volumes":[{"roomId":10,"value":-4,"unit":"m2"}],"rationale":"a"},
              {"workCode":"05.01","roomIds":[10,11],"volumes":[{"roomId":10,"value":12,"unit":"m2"}],"rationale":"b"}]}""");
        assertThat(has(p, "NEGATIVE_VOLUME")).isTrue();
        assertThat(has(p, "DUPLICATE_WORK_ROOM")).isTrue();
        assertThat(p.works()).hasSize(2);
        PlannedWork r10 = p.works().get(0);
        assertThat(r10.roomId()).isEqualTo(10L);
        assertThat(r10.volume()).isEqualByComparingTo("12");
        assertThat(r10.rationale()).isEqualTo("b");
        assertThat(p.works().get(1).volume()).isNull();
    }

    @Test
    void validatorRules() throws Exception {
        EstimatePlan p = resolve("""
            {"works":[
              {"workCode":"05.01","roomIds":[10],"alternativeGroup":"plaster","rationale":"x",
               "volumes":[{"roomId":10,"value":16,"unit":"m2","basis":"FLOOR_AREA"}]},
              {"workCode":"05.02","roomIds":[10],"alternativeGroup":"plaster","rationale":"y"}]}""");
        assertThat(has(p, "VOLUME_EXCEEDS_ROOM")).isTrue();
        assertThat(has(p, "ALTERNATIVES_BOTH_PRESENT")).isTrue();
        assertThat(has(p, "WORK_ROOM_TYPE_MISMATCH")).isTrue();
        assertThat(p.warnings()).filteredOn(w -> w.code().equals("ROOM_WITHOUT_WORKS"))
                .extracting(ReportWarning::roomId).containsExactly(11L);
        assertThat(p.works()).hasSize(2);
    }

    @Test
    void volumeAtExactlyOnePointFiveTimesIsAccepted() throws Exception {
        EstimatePlan p = resolve("""
            {"works":[{"workCode":"05.01","roomIds":[10,11],"rationale":"x",
              "volumes":[{"roomId":10,"value":15,"basis":"FLOOR_AREA"},{"roomId":11,"value":12,"basis":"FLOOR_AREA"}]}]}""");
        assertThat(has(p, "VOLUME_EXCEEDS_ROOM")).isFalse();
    }

    @Test
    void missingRationaleUnknownRoomAndNotes() throws Exception {
        EstimatePlan p = resolve("""
            {"works":[{"workCode":"05.01","roomIds":[10,777]}],
             "assumptions":[{"text":"h=2.7"}, "plain"],"questions":[{"text":"Q?","roomId":10}],
             "unpriced":[{"name":"Crane","unit":"h","reason":"NOT_IN_CATALOG"}]}""");
        assertThat(has(p, "RATIONALE_MISSING")).isTrue();
        assertThat(has(p, "UNKNOWN_ROOM")).isTrue();
        assertThat(p.assumptions()).extracting(EstimatePlan.Note::text).containsExactly("h=2.7", "plain");
        assertThat(p.questions().get(0).roomId()).isEqualTo(10L);
        assertThat(p.unpriced()).extracting(Unpriced::name).containsExactly("Crane");
    }

    @Test
    void garbageInputDoesNotThrow() throws Exception {
        assertThat(PlanResolver.resolve(null, null, null).works()).isEmpty();
        assertThat(resolve("[1,2]").works()).isEmpty();
        assertThat(resolve("{\"works\":[1,null,{\"workCode\":5,\"roomIds\":\"x\"}]}").works()).isEmpty();
    }
}
