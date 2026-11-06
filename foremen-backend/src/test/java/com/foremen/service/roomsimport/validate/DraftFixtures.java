package com.foremen.service.roomsimport.validate;

import com.foremen.config.roomsimport.RoomsImportProperties;
import com.foremen.controller.model.RoomCreateRequest;
import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.RoomCalculationService;
import com.foremen.service.roomsimport.model.DraftRoom;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Test helpers for building draft rooms. */
final class DraftFixtures {
    static final RoomsImportProperties.Validation THRESHOLDS = new RoomsImportProperties.Validation(0, 0, 0, 0);

    private DraftFixtures() {}

    static DraftValidator validator() {
        return new DraftValidator(new RoomCalculationService(), THRESHOLDS);
    }

    static RoomGeometry polygon(double... xy) {
        RoomGeometry g = new RoomGeometry();
        List<RoomGeometry.Vertex> vs = new ArrayList<>();
        List<RoomGeometry.Wall> ws = new ArrayList<>();
        for (int i = 0; i + 1 < xy.length; i += 2) {
            RoomGeometry.Vertex v = new RoomGeometry.Vertex();
            v.setX(xy[i]);
            v.setY(xy[i + 1]);
            vs.add(v);
            RoomGeometry.Wall w = new RoomGeometry.Wall();
            w.setOpenings(new ArrayList<>());
            ws.add(w);
        }
        g.setVertices(vs);
        g.setWalls(ws);
        return g;
    }

    static RoomGeometry rect(double w, double l) {
        return polygon(0, 0, w, 0, w, l, 0, l);
    }

    static void addOpening(RoomGeometry g, int wall, OpeningType type, int count, double h, double w) {
        RoomGeometry.Opening o = new RoomGeometry.Opening();
        o.setType(type);
        o.setCount(count);
        o.setHeight(h);
        o.setWidth(w);
        g.getWalls().get(wall).getOpenings().add(o);
    }

    static RoomCreateRequest request(RoomGeometry g, BigDecimal ceiling, Integer corners, String label, Long typeId) {
        return new RoomCreateRequest(1L, typeId, label, ceiling, corners, g, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    /** Scalar-only request (no geometry) with the given floor area. */
    static RoomCreateRequest scalar(BigDecimal floorArea, Integer corners) {
        return new RoomCreateRequest(1L, 1L, "a", new BigDecimal("2.6"), corners, null, floorArea, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    static DraftRoom draft(RoomCreateRequest room, Map<String, Object> stated, double confidence) {
        return new DraftRoom("r1", confidence, null, room, validator().calculate(room), stated, List.of(), List.of());
    }
}
