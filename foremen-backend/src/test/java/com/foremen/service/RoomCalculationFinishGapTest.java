package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.model.RoomMetrics;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FOR-05-02 Round 11 #2: the missing finish length is taken off the perimeter, the missing wall is not. */
class RoomCalculationFinishGapTest {

    private final RoomCalculationService service = new RoomCalculationService();

    /** 4 x 3 rectangle (perimeter 14), one door 1 x 2 on wall 0. */
    private static RoomGeometry rectangle(Double wallGap, Double finishGap) {
        RoomGeometry g = new RoomGeometry();
        double[][] pts = {{0, 0}, {4, 0}, {4, 3}, {0, 3}};
        List<RoomGeometry.Vertex> vs = new ArrayList<>();
        for (double[] p : pts) {
            RoomGeometry.Vertex v = new RoomGeometry.Vertex();
            v.setX(p[0]);
            v.setY(p[1]);
            vs.add(v);
        }
        g.setVertices(vs);
        List<RoomGeometry.Wall> walls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RoomGeometry.Wall w = new RoomGeometry.Wall();
            w.setOpenings(new ArrayList<>());
            walls.add(w);
        }
        walls.get(0).setWallGap(wallGap);
        walls.get(0).setFinishGap(finishGap);
        RoomGeometry.Opening door = new RoomGeometry.Opening();
        door.setType(OpeningType.DOOR);
        door.setCount(1);
        door.setHeight(2);
        door.setWidth(1);
        walls.get(0).getOpenings().add(door);
        g.setWalls(walls);
        return g;
    }

    @Test
    void wallAreaSubtractsFinishGapFromPerimeter() {
        RoomMetrics m = service.calculate(rectangle(null, 1.5), new BigDecimal("2.5"));
        // (14 - 1.5) * 2.5 - 2 = 29.25
        assertThat(m.wallArea()).isEqualByComparingTo("29.25");
        assertThat(m.finishGap()).isEqualByComparingTo("1.50");
    }

    @Test
    void wallGapIsSubtractedFromPerimeter() {
        RoomMetrics m = service.calculate(rectangle(2.0, null), new BigDecimal("2.5"));
        // (14 - 2) * 2.5 - 2 = 28.00
        assertThat(m.wallArea()).isEqualByComparingTo("28.00");
        assertThat(m.wallGap()).isEqualByComparingTo("2.00");
    }

    @Test
    void wallAreaNeverNegative() {
        RoomMetrics m = service.calculate(rectangle(null, 20.0), new BigDecimal("2.5"));
        assertThat(m.wallArea()).isEqualByComparingTo("0.00");
    }
}
