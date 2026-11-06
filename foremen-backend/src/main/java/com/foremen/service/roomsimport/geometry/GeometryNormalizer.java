package com.foremen.service.roomsimport.geometry;

import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure normaliser for geometry returned by the measuring step (FOR-05-02, Requirement 5.4).
 *
 * <p>Converts to metres, drops consecutive duplicate vertices, orients the polygon
 * counter-clockwise, aligns {@code walls.size() == vertices.size()}, drops invalid openings
 * ({@link WarningCodes#OPENING_DROPPED}) and can synthesise a rectangle from text dimensions
 * ({@link WarningCodes#GEOMETRY_SYNTHESIZED}). The input is never mutated.
 */
@Component
public class GeometryNormalizer {

    /** Value of {@code source.derivation} for a rectangle synthesised from text. */
    public static final String DERIVATION_RECTANGLE_FROM_TEXT = "RECTANGLE_FROM_TEXT";

    private static final double EPS = 1e-9;

    private static final Pattern DIMENSIONS = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(?:m|cm|mm)?\\s*[x×*хХ]\\s*(\\d+(?:[.,]\\d+)?)\\s*(m|cm|mm)?",
            Pattern.CASE_INSENSITIVE);

    /** Normalisation outcome; {@code geometry} is {@code null} when no valid polygon remains. */
    public record Result(RoomGeometry geometry, List<Warning> warnings, String derivation) {}

    /** Normalises geometry given in metres. */
    public Result normalize(RoomGeometry input, String roomTempId) {
        return normalize(input, "m", roomTempId);
    }

    /**
     * @param unit one of {@code m, cm, mm, ft} (null/unknown ⇒ metres)
     */
    public Result normalize(RoomGeometry input, String unit, String roomTempId) {
        List<Warning> warnings = new ArrayList<>();
        if (input == null || input.getVertices() == null) {
            return new Result(null, warnings, null);
        }
        double factor = factorOf(unit);

        // 1. Copy + scale vertices; reject non-finite coordinates.
        List<RoomGeometry.Vertex> vertices = new ArrayList<>();
        for (RoomGeometry.Vertex v : input.getVertices()) {
            if (v == null || !Double.isFinite(v.getX()) || !Double.isFinite(v.getY())) {
                return new Result(null, warnings, null);
            }
            vertices.add(vertex(v.getX() * factor, v.getY() * factor));
        }
        int originalCount = vertices.size();

        // 2. Align walls to the original vertex count (pad with empty / trim extras), scaling them.
        List<RoomGeometry.Wall> inWalls = input.getWalls() == null ? List.of() : input.getWalls();
        if (inWalls.size() > originalCount) {
            warnings.add(new Warning(WarningCodes.GEOMETRY_INVALID, Severity.WARNING, roomTempId, "geometry.walls",
                    Map.of("reason", "WALLS_TRIMMED", "walls", inWalls.size(), "vertices", originalCount), null));
        }
        List<RoomGeometry.Wall> walls = new ArrayList<>();
        for (int i = 0; i < originalCount; i++) {
            RoomGeometry.Wall w = i < inWalls.size() ? inWalls.get(i) : null;
            walls.add(copyWall(w, factor, roomTempId, i, warnings));
        }

        // 3. Drop consecutive duplicate vertices (wall of the duplicate replaces the zero-length one).
        List<RoomGeometry.Vertex> dv = new ArrayList<>();
        List<RoomGeometry.Wall> dw = new ArrayList<>();
        for (int i = 0; i < vertices.size(); i++) {
            RoomGeometry.Vertex v = vertices.get(i);
            if (!dv.isEmpty() && same(dv.get(dv.size() - 1), v)) {
                dw.set(dw.size() - 1, walls.get(i));
            } else {
                dv.add(v);
                dw.add(walls.get(i));
            }
        }
        while (dv.size() > 1 && same(dv.get(dv.size() - 1), dv.get(0))) {
            dv.remove(dv.size() - 1);
            dw.remove(dw.size() - 1);
        }
        if (dv.size() < 3) {
            return new Result(null, warnings, null);
        }

        // 4. Orient counter-clockwise (reverse keeping vertex 0, remapping walls and offsets).
        if (signedArea(dv) < -EPS) {
            int n = dv.size();
            List<RoomGeometry.Vertex> rv = new ArrayList<>();
            List<RoomGeometry.Wall> rw = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                rv.add(dv.get((n - k) % n));
                RoomGeometry.Wall src = dw.get((n - k - 1) % n);
                int srcIdx = (n - k - 1) % n;
                double len = dist(dv.get(srcIdx), dv.get((srcIdx + 1) % n));
                rw.add(mirrorWall(src, len));
            }
            dv = rv;
            dw = rw;
        }

        RoomGeometry out = new RoomGeometry();
        out.setVertices(dv);
        out.setWalls(dw);
        return new Result(out, warnings, null);
    }

    /** Synthesises an axis-aligned rectangle {@code width × length} (metres) from stated text dimensions. */
    public Result synthesizeRectangle(double width, double length, String roomTempId) {
        List<Warning> warnings = new ArrayList<>();
        if (!(width > 0) || !(length > 0) || !Double.isFinite(width) || !Double.isFinite(length)) {
            return new Result(null, warnings, null);
        }
        RoomGeometry g = new RoomGeometry();
        g.setVertices(new ArrayList<>(List.of(vertex(0, 0), vertex(width, 0), vertex(width, length), vertex(0, length))));
        List<RoomGeometry.Wall> walls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            walls.add(emptyWall());
        }
        g.setWalls(walls);
        warnings.add(new Warning(WarningCodes.GEOMETRY_SYNTHESIZED, Severity.INFO, roomTempId, "geometry",
                Map.of("derivation", DERIVATION_RECTANGLE_FROM_TEXT, "width", width, "length", length), null));
        return new Result(g, warnings, DERIVATION_RECTANGLE_FROM_TEXT);
    }

    /**
     * Extracts a single "a × b" pair (decimal comma allowed; optional m/cm/mm suffix, default metres)
     * and returns {@code [width, length]} in metres. Empty when there is none or more than one pair.
     */
    public Optional<double[]> parseRectangleDimensions(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = DIMENSIONS.matcher(text);
        double[] found = null;
        while (m.find()) {
            if (found != null) {
                return Optional.empty();
            }
            double f = factorOf(m.group(3));
            double a = Double.parseDouble(m.group(1).replace(',', '.')) * f;
            double b = Double.parseDouble(m.group(2).replace(',', '.')) * f;
            if (a > 0 && b > 0) {
                found = new double[] {a, b};
            }
        }
        return Optional.ofNullable(found);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static double factorOf(String unit) {
        if (unit == null) {
            return 1.0;
        }
        return switch (unit.trim().toLowerCase(Locale.ROOT)) {
            case "cm" -> 0.01;
            case "mm" -> 0.001;
            case "ft", "feet" -> 0.3048;
            default -> 1.0;
        };
    }

    private RoomGeometry.Wall copyWall(RoomGeometry.Wall src, double factor, String tempId, int wallIdx,
                                       List<Warning> warnings) {
        RoomGeometry.Wall w = emptyWall();
        if (src == null) {
            return w;
        }
        w.setWallGap(scale(src.getWallGap(), factor));
        w.setFinishGap(scale(src.getFinishGap(), factor));
        if (src.getOpenings() != null) {
            int idx = 0;
            for (RoomGeometry.Opening o : src.getOpenings()) {
                String reason = invalidReason(o, factor);
                if (reason != null) {
                    warnings.add(new Warning(WarningCodes.OPENING_DROPPED, Severity.WARNING, tempId,
                            "geometry.walls[" + wallIdx + "].openings[" + idx + "]",
                            Map.of("reason", reason, "wall", wallIdx), null));
                } else {
                    RoomGeometry.Opening c = new RoomGeometry.Opening();
                    c.setType(o.getType());
                    c.setCount(o.getCount());
                    c.setHeight(o.getHeight() * factor);
                    c.setWidth(o.getWidth() * factor);
                    Double off = scale(o.getOffset(), factor);
                    c.setOffset(off != null && off >= 0 ? off : null);
                    w.getOpenings().add(c);
                }
                idx++;
            }
        }
        return w;
    }

    private static String invalidReason(RoomGeometry.Opening o, double factor) {
        if (o == null || o.getType() == null) {
            return "INVALID_OPENING";
        }
        if (o.getCount() <= 0 || !(o.getWidth() * factor > 0) || !(o.getHeight() * factor > 0)
                || !Double.isFinite(o.getWidth()) || !Double.isFinite(o.getHeight())) {
            return "NON_POSITIVE_SIZE";
        }
        return null;
    }

    /** Wall reversed in direction: opening offsets are measured from the new start vertex. */
    private static RoomGeometry.Wall mirrorWall(RoomGeometry.Wall src, double wallLength) {
        RoomGeometry.Wall w = emptyWall();
        w.setWallGap(src.getWallGap());
        w.setFinishGap(src.getFinishGap());
        for (RoomGeometry.Opening o : src.getOpenings()) {
            RoomGeometry.Opening c = new RoomGeometry.Opening();
            c.setType(o.getType());
            c.setCount(o.getCount());
            c.setHeight(o.getHeight());
            c.setWidth(o.getWidth());
            Double mirrored = null;
            if (o.getOffset() != null && o.getCount() == 1) {
                double m = wallLength - o.getOffset() - o.getWidth();
                mirrored = m >= 0 ? m : null;
            }
            c.setOffset(mirrored);
            w.getOpenings().add(c);
        }
        return w;
    }

    private static Double scale(Double v, double factor) {
        return v == null ? null : v * factor;
    }

    private static RoomGeometry.Wall emptyWall() {
        RoomGeometry.Wall w = new RoomGeometry.Wall();
        w.setOpenings(new ArrayList<>());
        return w;
    }

    private static RoomGeometry.Vertex vertex(double x, double y) {
        RoomGeometry.Vertex v = new RoomGeometry.Vertex();
        v.setX(x);
        v.setY(y);
        return v;
    }

    private static boolean same(RoomGeometry.Vertex a, RoomGeometry.Vertex b) {
        return Math.abs(a.getX() - b.getX()) <= EPS && Math.abs(a.getY() - b.getY()) <= EPS;
    }

    private static double dist(RoomGeometry.Vertex a, RoomGeometry.Vertex b) {
        return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
    }

    /** Shoelace signed area (positive for counter-clockwise). */
    public static double signedArea(List<RoomGeometry.Vertex> vs) {
        double s = 0;
        for (int i = 0; i < vs.size(); i++) {
            RoomGeometry.Vertex a = vs.get(i);
            RoomGeometry.Vertex b = vs.get((i + 1) % vs.size());
            s += a.getX() * b.getY() - b.getX() * a.getY();
        }
        return s / 2.0;
    }
}
