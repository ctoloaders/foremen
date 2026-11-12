package com.foremen.service.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.service.dashboard.GridMath.Fit;
import com.foremen.service.dashboard.GridMath.Rect;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract between {@link GridMath} and the shared vectors file that the frontend {@code lib/grid.ts}
 * is also checked against (FOR-10 design A1.3).
 * <b>Validates: Requirements 21.10</b>
 */
class GridVectorsContractTest {

    private static JsonNode load() throws Exception {
        try (InputStream in = GridVectorsContractTest.class.getResourceAsStream("/dashboard/grid-vectors.json")) {
            assertThat(in).as("grid-vectors.json on the test classpath").isNotNull();
            return new ObjectMapper().readTree(in);
        }
    }

    private static List<Integer> ints(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asInt()));
        return out;
    }

    @Test
    void javaReferenceMatchesSharedVectors() throws Exception {
        JsonNode root = load();
        assertThat(root.get("version").asInt()).isEqualTo(1);
        JsonNode cases = root.get("cases");
        assertThat(cases.size()).isGreaterThanOrEqualTo(10);

        for (JsonNode c : cases) {
            String name = c.get("name").asText();
            List<Rect> placements = new ArrayList<>();
            c.get("placements").forEach(p -> placements.add(
                    new Rect(p.get("cell").asInt(), p.get("colSpan").asInt(), p.get("rowSpan").asInt())));
            long occupied = GridMath.occupied(placements);

            List<Integer> occupiedCells = new ArrayList<>();
            for (int i = 0; i < GridMath.CELLS; i++) {
                if ((occupied & (1L << i)) != 0) {
                    occupiedCells.add(i);
                }
            }
            assertThat(occupiedCells).as(name + ": occupiedCells").isEqualTo(ints(c.get("occupiedCells")));
            assertThat(GridMath.freeCells(occupied)).as(name + ": freeCells").isEqualTo(ints(c.get("freeCells")));
            assertThat(GridMath.lastOccupiedRow(placements)).as(name + ": lastOccupiedRow")
                    .isEqualTo(c.get("lastOccupiedRow").asInt());
            assertThat(GridMath.visibleRows(placements)).as(name + ": visibleRows")
                    .isEqualTo(c.get("visibleRows").asInt());

            for (JsonNode q : c.get("queries")) {
                int cell = q.get("cell").asInt();
                String where = name + " @cell " + cell;

                List<Fit> expectedFits = new ArrayList<>();
                q.get("maximalFits").forEach(f -> expectedFits.add(new Fit(f.get(0).asInt(), f.get(1).asInt())));
                assertThat(GridMath.maximalFits(occupied, cell)).as(where + ": maximalFits").isEqualTo(expectedFits);

                JsonNode fs = q.get("fitSize");
                Optional<Fit> expectedFit = fs.isNull()
                        ? Optional.empty() : Optional.of(new Fit(fs.get(0).asInt(), fs.get(1).asInt()));
                assertThat(GridMath.fitSize(occupied, cell)).as(where + ": fitSize").isEqualTo(expectedFit);

                for (JsonNode e : q.get("classify")) {
                    int cs = e.get("colSpan").asInt();
                    int rs = e.get("rowSpan").asInt();
                    assertThat(GridMath.classify(occupied, cell, cs, rs).name())
                            .as(where + ": classify " + cs + "x" + rs)
                            .isEqualTo(e.get("result").asText());
                }
            }
        }
    }

    @Test
    void coversRequiredScenarios() throws Exception {
        JsonNode cases = load().get("cases");
        List<String> names = new ArrayList<>();
        cases.forEach(c -> names.add(c.get("name").asText()));
        assertThat(names).anyMatch(n -> n.startsWith("empty grid"));
        assertThat(names).anyMatch(n -> n.startsWith("full grid"));
        assertThat(names).anyMatch(n -> n.contains("single"));
        assertThat(names).anyMatch(n -> n.contains("holes in a row"));
        assertThat(names).anyMatch(n -> n.contains("tall thin gap"));
        assertThat(names).anyMatch(n -> n.contains("edge cells 3, 32 and 35"));
        assertThat(names).anyMatch(n -> n.contains("2x1 fits while Fit_Size is 1xN"));

        // The 2x1-fits-while-Fit_Size-is-1xN case must really exhibit that property at cell 1.
        for (JsonNode c : cases) {
            if (c.get("name").asText().contains("2x1 fits while Fit_Size is 1xN")) {
                for (JsonNode q : c.get("queries")) {
                    if (q.get("cell").asInt() == 1) {
                        assertThat(q.get("fitSize").get(0).asInt()).isEqualTo(1);
                        boolean twoByOneOk = false;
                        for (JsonNode e : q.get("classify")) {
                            if (e.get("colSpan").asInt() == 2 && e.get("rowSpan").asInt() == 1) {
                                twoByOneOk = "OK".equals(e.get("result").asText());
                            }
                        }
                        assertThat(twoByOneOk).isTrue();
                    }
                }
            }
        }
    }
}
