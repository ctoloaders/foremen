package com.foremen.service.roomsimport.ai;

import com.foremen.service.roomsimport.ai.MeasureUnits.Kind;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural validation of parsed model responses. Business sanity (area, openings) is the job of the
 * draft validators; this only rejects answers that cannot be processed at all and are worth a retry.
 * Each method returns a short problem description (never user content) or {@code null} when valid.
 */
public final class RoomsVisionResponseValidator {

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
        if (r.geometryUnit() != null) {
            if (!MeasureUnits.isLengthUnit(r.geometryUnit())) {
                return MeasureUnits.isAreaUnit(r.geometryUnit())
                        ? "area unit on a length field" : "unknown unit";
            }
            if (!isUnitSource(r.geometryUnitSource())) {
                return "missing unitSource";
            }
        } else if (!r.vertices().isEmpty()) {
            return "missing geometryUnit";
        }
        String measuredProblem = validateMeasured(r.ceilingHeight(), Kind.LENGTH);
        if (measuredProblem == null) {
            measuredProblem = validateMeasured(r.narrowPlace(), Kind.LENGTH);
        }
        if (measuredProblem != null) {
            return measuredProblem;
        }
        if (r.internalCorners() != null && r.internalCorners() < 0) {
            return "negative count";
        }
        for (Map.Entry<String, Measured> e : r.stated().entrySet()) {
            Kind kind = MeasureUnits.STATED_FIELDS.get(e.getKey());
            measuredProblem = validateMeasured(e.getValue(), kind);
            if (measuredProblem != null) {
                return measuredProblem;
            }
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
            if (w == null) {
                continue;
            }
            measuredProblem = validateMeasured(w.wallGap(), Kind.LENGTH);
            if (measuredProblem == null) {
                measuredProblem = validateMeasured(w.finishGap(), Kind.LENGTH);
            }
            if (measuredProblem != null) {
                return measuredProblem;
            }
            if (w.openings() == null) {
                continue;
            }
            for (MeasureResponse.Opening o : w.openings()) {
                if (o == null || o.type() == null || !OPENING_TYPES.contains(o.type())) {
                    return "unknown opening type";
                }
                if (o.count() != null && o.count() < 0) {
                    return "negative count";
                }
                for (Measured m : new Measured[] {o.height(), o.width(), o.offset()}) {
                    measuredProblem = validateMeasured(m, Kind.LENGTH);
                    if (measuredProblem != null) {
                        return measuredProblem;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isUnitSource(String s) {
        return Measured.EXPLICIT.equals(s) || Measured.INFERRED.equals(s);
    }

    /**
     * Checks one {@link Measured}. {@code kind == null} (an unrecognised stated field) accepts either a
     * length or an area unit. A {@code null} measured or a {@code null} value means "not present".
     */
    private static String validateMeasured(Measured m, Kind kind) {
        if (m == null || m.value() == null) {
            return null;
        }
        if (!Double.isFinite(m.value())) {
            return "non-finite value";
        }
        if (kind == Kind.COUNT) {
            return m.value() < 0 ? "negative count" : null;
        }
        String unit = m.unit();
        boolean length = MeasureUnits.isLengthUnit(unit);
        boolean area = MeasureUnits.isAreaUnit(unit);
        if (!length && !area) {
            return "unknown unit";
        }
        if (kind == Kind.LENGTH && area) {
            return "area unit on a length field";
        }
        if (kind == Kind.AREA && length) {
            return "length unit on an area field";
        }
        if (!isUnitSource(m.unitSource())) {
            return "missing unitSource";
        }
        return null;
    }
}
