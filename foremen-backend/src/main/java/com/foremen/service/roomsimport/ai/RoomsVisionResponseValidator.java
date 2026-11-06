package com.foremen.service.roomsimport.ai;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural validation of parsed model responses. Business sanity (area, openings) is the job of the
 * draft validators; this only rejects answers that cannot be processed at all and are worth a retry.
 * Each method returns a short problem description (never user content) or {@code null} when valid.
 */
public final class RoomsVisionResponseValidator {

    private static final Set<String> UNITS = Set.of("m", "cm", "mm");
    private static final Set<String> OPENING_TYPES = Set.of("DOOR", "WINDOW");

    private RoomsVisionResponseValidator() {}

    public static String validate(DetectResponse r) {
        if (r == null) {
            return "empty response";
        }
        Set<String> ids = new HashSet<>();
        for (DetectResponse.DetectedRoom room : r.rooms()) {
            if (room == null || room.tempId() == null || room.tempId().isBlank()) {
                return "room without tempId";
            }
            if (!ids.add(room.tempId())) {
                return "duplicate tempId";
            }
            if (room.confidence() != null && !(room.confidence() >= 0 && room.confidence() <= 1)) {
                return "confidence out of range";
            }
            String bbox = validateBbox(room.bbox());
            if (bbox != null) {
                return bbox;
            }
        }
        if (r.statedRoomCount() != null && r.statedRoomCount() < 0) {
            return "negative statedRoomCount";
        }
        return null;
    }

    private static String validateBbox(List<Double> b) {
        if (b == null) {
            return null;
        }
        if (b.size() != 4) {
            return "bbox must have 4 numbers";
        }
        for (Double d : b) {
            if (d == null || !Double.isFinite(d) || d < 0 || d > 1000) {
                return "bbox value out of range";
            }
        }
        if (b.get(0) >= b.get(2) || b.get(1) >= b.get(3)) {
            return "bbox is empty";
        }
        return null;
    }

    public static String validate(MeasureResponse r) {
        if (r == null) {
            return "empty response";
        }
        if (r.unit() != null && !UNITS.contains(r.unit())) {
            return "unknown unit";
        }
        for (MeasureResponse.Vertex v : r.vertices()) {
            if (v == null || v.x() == null || v.y() == null
                    || !Double.isFinite(v.x()) || !Double.isFinite(v.y())) {
                return "invalid vertex";
            }
        }
        if (r.confidence() != null && !(r.confidence() >= 0 && r.confidence() <= 1)) {
            return "confidence out of range";
        }
        for (MeasureResponse.Wall w : r.walls()) {
            if (w == null || w.openings() == null) {
                continue;
            }
            for (MeasureResponse.Opening o : w.openings()) {
                if (o == null || o.type() == null || !OPENING_TYPES.contains(o.type())) {
                    return "unknown opening type";
                }
            }
        }
        return null;
    }
}
