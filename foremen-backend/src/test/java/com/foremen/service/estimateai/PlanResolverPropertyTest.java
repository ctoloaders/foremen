package com.foremen.service.estimateai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.service.estimateai.PlanCatalog.CatalogWork;
import com.foremen.service.estimateai.PlanCatalog.RoomFacts;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Properties of {@link PlanResolver}: never a zero-price line, never throws, idempotent merge.
 * <b>Validates: Requirements 5.3, 5.4, 5.5, 6.5</b>
 */
class PlanResolverPropertyTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static final Map<String, CatalogWork> CATALOG = Map.of(
            "A", new CatalogWork(1L, "A", "A", true, new BigDecimal("10"), false, Set.of()),
            "B", new CatalogWork(2L, "B", "B", true, null, false, Set.of()),
            "C", new CatalogWork(3L, "C", "C", true, BigDecimal.ZERO, false, Set.of(5L)),
            "D", new CatalogWork(4L, "D", "D", true, new BigDecimal("3"), true, Set.of()),
            "E", new CatalogWork(5L, "E", "E", false, new BigDecimal("3"), false, Set.of()),
            "F", new CatalogWork(6L, "F", "F", true, new BigDecimal("7"), false, Set.of(5L)));

    private static final List<RoomFacts> ROOMS = List.of(
            new RoomFacts(1L, 5L, new BigDecimal("10"), new BigDecimal("30")),
            new RoomFacts(2L, 6L, null, null));

    @Provide
    Arbitrary<JsonNode> plans() {
        Arbitrary<String> codes = Arbitraries.of("A", "B", "C", "D", "E", "F", "ZZ");
        Arbitrary<Long> roomIds = Arbitraries.longs().between(0, 3);
        Arbitrary<Double> values = Arbitraries.doubles().between(-50, 100);
        Arbitrary<ObjectNode> work = codes.flatMap(c -> roomIds.list().ofMaxSize(3)
                .flatMap(ids -> values.list().ofMaxSize(3).map(vs -> {
                    ObjectNode w = OM.createObjectNode();
                    w.put("workCode", c);
                    ArrayNode rs = w.putArray("roomIds");
                    ids.forEach(rs::add);
                    ArrayNode vols = w.putArray("volumes");
                    for (int i = 0; i < vs.size() && i < ids.size(); i++) {
                        ObjectNode v = vols.addObject();
                        v.put("roomId", ids.get(i));
                        v.put("value", vs.get(i));
                        v.put("basis", i % 2 == 0 ? "FLOOR_AREA" : "WALL_AREA");
                    }
                    return w;
                })));
        return work.list().ofMaxSize(8).map(ws -> {
            ObjectNode root = OM.createObjectNode();
            ArrayNode a = root.putArray("works");
            ws.forEach(a::add);
            return root;
        });
    }

    @Property
    void neverEmitsZeroPriceLineAndNeverThrows(@ForAll("plans") JsonNode raw) {
        EstimatePlan p = PlanResolver.resolve(raw, ROOMS, CATALOG);
        Set<String> keys = new HashSet<>();
        for (PlannedWork w : p.works()) {
            CatalogWork cw = CATALOG.get(w.workCode());
            assertThat(cw).isNotNull();
            assertThat(cw.active()).isTrue();
            assertThat(cw.percentageUnit()).isFalse();
            assertThat(cw.price()).isNotNull();
            assertThat(cw.price().signum()).isPositive();
            if (w.volume() != null) {
                assertThat(w.volume().signum()).isGreaterThanOrEqualTo(0);
            }
            assertThat(keys.add(w.workCode() + "|" + w.roomId())).isTrue();
        }
    }

    @Property
    void mergeIsIdempotent(@ForAll("plans") JsonNode raw) {
        EstimatePlan p = PlanResolver.resolve(raw, ROOMS, CATALOG);
        PlanResolver.Merged once = PlanResolver.merge(p.works());
        PlanResolver.Merged twice = PlanResolver.merge(once.works());
        assertThat(once.works()).isEqualTo(p.works());
        assertThat(twice.works()).isEqualTo(once.works());
        assertThat(twice.warnings()).isEmpty();
    }
}
