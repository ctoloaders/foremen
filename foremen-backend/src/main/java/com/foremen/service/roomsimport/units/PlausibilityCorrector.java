package com.foremen.service.roomsimport.units;

import com.foremen.config.roomsimport.RoomsImportProperties;
import com.foremen.config.roomsimport.RoomsImportProperties.Plausibility;
import com.foremen.config.roomsimport.RoomsImportProperties.Range;
import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;
import com.foremen.service.roomsimport.units.PlausibilityInput.Quantity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoublePredicate;

import static com.foremen.service.roomsimport.units.PlausibilityInput.*;

/**
 * Deterministic plausibility pass (FOR-05-02, Requirement 25 / design B5a). Takes values that were
 * already converted to target units and, for every value outside its configured real-world range,
 * tries a single decimal rescale (lengths ÷10, ÷100, ÷1000, ×10, ×100; areas ÷100, ÷10⁴, ÷10⁶).
 * A candidate is kept only if it is in range AND passes the cross-checks (door/window height below
 * the ceiling, opening width within its wall, stated floor area within tolerance of the polygon
 * area, door/window area within the wall area). Exactly one fitting candidate ⇒ the value is replaced
 * and an INFO {@code UNIT_CORRECTED} is emitted; none or several ⇒ the value is cleared and a WARNING
 * {@code DIMENSION_OUT_OF_RANGE} is emitted.
 *
 * <p>The outline is rescaled as one unit (vertices and opening offsets together, field
 * {@code "geometry"}). Order: geometry, ceiling, openings and wall gaps, then scalars. In-range
 * values are never touched. The class never calls an LLM, never mutates its input and never throws.
 *
 * <p>Deviation notes: an opening width/height of a geometry opening cannot be {@code null} (primitive),
 * so a cleared one becomes {@code 0.0}, which the geometry normaliser drops later; opening
 * {@code count} is not range-checked.
 *
 * <p>{@code factor} on {@code UNIT_CORRECTED} is {@code original / corrected}: {@code 1000} for ÷1000,
 * {@code 0.1} for ×10.
 */
@Component
public class PlausibilityCorrector {

    /** Code of the INFO warning emitted on a successful rescale. */
    public static final String UNIT_CORRECTED = WarningCodes.UNIT_CORRECTED;

    private static final double DEFAULT_AREA_TOLERANCE = 0.02;

    /** A rescale: {@code corrected = divide ? v / operand : v * operand}. */
    private record Cand(double operand, boolean divide) {
        double apply(double v) {
            return divide ? v / operand : v * operand;
        }

        double factor() {
            return divide ? operand : 1.0 / operand;
        }
    }

    private static final List<Cand> LENGTH_CANDS = List.of(
            new Cand(10, true), new Cand(100, true), new Cand(1000, true),
            new Cand(10, false), new Cand(100, false));
    private static final List<Cand> AREA_CANDS = List.of(
            new Cand(100, true), new Cand(1e4, true), new Cand(1e6, true));
    private static final List<Cand> NO_CANDS = List.of();

    private final Plausibility ranges;
    private final double areaTolerance;

    @Autowired
    public PlausibilityCorrector(RoomsImportProperties props) {
        this(props.plausibility(), props.validation().areaTolerance());
    }

    public PlausibilityCorrector(Plausibility ranges, double areaTolerance) {
        this.ranges = ranges == null ? Plausibility.defaults() : ranges;
        this.areaTolerance = areaTolerance > 0 ? areaTolerance : DEFAULT_AREA_TOLERANCE;
    }

    /** Default ranges and area tolerance. */
    public PlausibilityCorrector() {
        this(Plausibility.defaults(), DEFAULT_AREA_TOLERANCE);
    }

    /**
     * Corrects {@code input}. Returns a corrected deep copy plus warnings; if {@code input} is
     * {@code null} an empty input is returned; on any unexpected internal error the input is returned
     * unchanged without warnings.
     */
    public PlausibilityResult correct(PlausibilityInput input) {
        if (input == null) {
            return new PlausibilityResult(new PlausibilityInput(null, null, null, null), List.of());
        }
        try {
            return new Run(input).run();
        } catch (RuntimeException e) {
            return new PlausibilityResult(input, List.of());
        }
    }

    // ------------------------------------------------------------------------------------------

    private final class Run {
        private final PlausibilityInput in;
        private final List<Warning> warnings = new ArrayList<>();
        private final Map<String, Quantity> values = new LinkedHashMap<>();
        private RoomGeometry geometry;

        Run(PlausibilityInput in) {
            this.in = in;
            values.putAll(in.values());
            geometry = copy(in.geometry());
        }

        PlausibilityResult run() {
            correctGeometry();
            correctCeiling();
            correctOpeningsAndGaps();
            correctScalars();
            return new PlausibilityResult(new PlausibilityInput(in.roomTempId(), values, geometry,
                    in.geometryUnit()), List.copyOf(warnings));
        }

        // ---- geometry -------------------------------------------------------------------------

        private void correctGeometry() {
            if (geometry == null || geometry.getVertices() == null || geometry.getVertices().size() < 3
                    || geometry.getVertices().stream().anyMatch(v -> v == null)) {
                return;
            }
            List<RoomGeometry.Vertex> vs = geometry.getVertices();
            double[] m = metrics(vs);
            if (outlinePlausible(m)) {
                return;
            }
            Double stated = value(FLOOR_AREA);
            boolean useStated = stated != null && ranges.floorArea().contains(stated);
            List<Cand> fits = new ArrayList<>();
            for (Cand c : LENGTH_CANDS) {
                double[] cm = metrics(vs, c);
                if (!outlinePlausible(cm)) {
                    continue;
                }
                if (useStated && Math.abs(cm[1] - stated) > areaTolerance * stated) {
                    continue;
                }
                fits.add(c);
            }
            if (fits.size() == 1) {
                Cand c = fits.get(0);
                RoomGeometry original = copy(geometry);
                for (RoomGeometry.Vertex v : vs) {
                    v.setX(c.apply(v.getX()));
                    v.setY(c.apply(v.getY()));
                }
                if (geometry.getWalls() != null) {
                    for (RoomGeometry.Wall w : geometry.getWalls()) {
                        if (w != null && w.getOpenings() != null) {
                            for (RoomGeometry.Opening o : w.getOpenings()) {
                                if (o != null && o.getOffset() != null) {
                                    o.setOffset(c.apply(o.getOffset()));
                                }
                            }
                        }
                    }
                }
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("field", "geometry");
                params.put("original", m[0]);
                params.put("originalUnit", in.geometryUnit());
                params.put("corrected", c.apply(m[0]));
                params.put("factor", c.factor());
                Map<String, Object> fix = new LinkedHashMap<>();
                fix.put("geometry", original);
                warnings.add(new Warning(UNIT_CORRECTED, Severity.INFO, in.roomTempId(), "geometry", params, fix));
            } else {
                geometry = null;
                outOfRange("geometry", m[0], in.geometryUnit(), ranges.wallLength());
            }
        }

        private boolean outlinePlausible(double[] m) {
            return ranges.wallLength().contains(m[0]) && ranges.floorArea().contains(m[1]);
        }

        // ---- ceiling --------------------------------------------------------------------------

        private void correctCeiling() {
            Double v = value(CEILING_HEIGHT);
            if (v == null) {
                return;
            }
            double hint = Double.NEGATIVE_INFINITY;
            hint = Math.max(hint, knownHeight(value(DOOR_HEIGHT), ranges.doorHeight()));
            hint = Math.max(hint, knownHeight(value(WINDOW_HEIGHT), ranges.windowHeight()));
            if (geometry != null && geometry.getWalls() != null) {
                for (RoomGeometry.Wall w : geometry.getWalls()) {
                    if (w == null || w.getOpenings() == null) {
                        continue;
                    }
                    for (RoomGeometry.Opening o : w.getOpenings()) {
                        if (o != null && o.getType() != null) {
                            Range r = o.getType() == OpeningType.WINDOW ? ranges.windowHeight() : ranges.doorHeight();
                            hint = Math.max(hint, knownHeight(o.getHeight(), r));
                        }
                    }
                }
            }
            final double max = hint;
            set(CEILING_HEIGHT, check(CEILING_HEIGHT, v, unit(CEILING_HEIGHT), ranges.ceilingHeight(),
                    LENGTH_CANDS, c -> c > max));
        }

        private double knownHeight(Double h, Range r) {
            return h != null && r.contains(h) ? h : Double.NEGATIVE_INFINITY;
        }

        // ---- openings and wall gaps -----------------------------------------------------------

        private void correctOpeningsAndGaps() {
            if (geometry == null || geometry.getWalls() == null) {
                return;
            }
            Double ceiling = value(CEILING_HEIGHT);
            List<RoomGeometry.Wall> walls = geometry.getWalls();
            for (int i = 0; i < walls.size(); i++) {
                RoomGeometry.Wall w = walls.get(i);
                if (w == null) {
                    continue;
                }
                Double len = wallLength(i);
                String base = "geometry.walls[" + i + "]";
                DoublePredicate withinWall = c -> len == null || c <= len;
                if (w.getWallGap() != null) {
                    w.setWallGap(check(base + ".wallGap", w.getWallGap(), in.geometryUnit(), ranges.gap(),
                            LENGTH_CANDS, withinWall));
                }
                if (w.getFinishGap() != null) {
                    w.setFinishGap(check(base + ".finishGap", w.getFinishGap(), in.geometryUnit(), ranges.gap(),
                            LENGTH_CANDS, withinWall));
                }
                if (w.getOpenings() == null) {
                    continue;
                }
                for (int j = 0; j < w.getOpenings().size(); j++) {
                    RoomGeometry.Opening o = w.getOpenings().get(j);
                    if (o == null || o.getType() == null) {
                        continue;
                    }
                    boolean window = o.getType() == OpeningType.WINDOW;
                    String ob = base + ".openings[" + j + "]";
                    String unit = in.geometryUnit();
                    if (!(o.getHeight() <= 0)) {
                        Double r = check(ob + ".height", o.getHeight(), unit,
                                window ? ranges.windowHeight() : ranges.doorHeight(), LENGTH_CANDS,
                                c -> ceiling == null || c < ceiling);
                        o.setHeight(r == null ? 0.0 : r);
                    }
                    if (!(o.getWidth() <= 0)) {
                        Double r = check(ob + ".width", o.getWidth(), unit,
                                window ? ranges.windowWidth() : ranges.doorWidth(), LENGTH_CANDS, withinWall);
                        o.setWidth(r == null ? 0.0 : r);
                    }
                    if (o.getOffset() != null) {
                        double top = len != null ? len : ranges.wallLength().max();
                        o.setOffset(check(ob + ".offset", o.getOffset(), unit, new Range(0, top),
                                LENGTH_CANDS, c -> true));
                    }
                }
            }
        }

        private Double wallLength(int i) {
            List<RoomGeometry.Vertex> vs = geometry.getVertices();
            if (vs == null || i >= vs.size() || vs.size() < 2 || vs.stream().anyMatch(v -> v == null)) {
                return null;
            }
            RoomGeometry.Vertex a = vs.get(i);
            RoomGeometry.Vertex b = vs.get((i + 1) % vs.size());
            double d = Math.hypot(b.getX() - a.getX(), b.getY() - a.getY());
            return Double.isFinite(d) ? d : null;
        }

        private Double maxWallLength() {
            if (geometry == null || geometry.getVertices() == null) {
                return null;
            }
            Double max = null;
            for (int i = 0; i < geometry.getVertices().size(); i++) {
                Double l = wallLength(i);
                if (l != null && (max == null || l > max)) {
                    max = l;
                }
            }
            return max;
        }

        // ---- scalars --------------------------------------------------------------------------

        private void correctScalars() {
            Double ceiling = value(CEILING_HEIGHT);
            Double maxWall = maxWallLength();
            DoublePredicate belowCeiling = c -> ceiling == null || c < ceiling;
            DoublePredicate withinWall = c -> maxWall == null || c <= maxWall;

            scalar(DOOR_HEIGHT, ranges.doorHeight(), LENGTH_CANDS, belowCeiling);
            scalar(WINDOW_HEIGHT, ranges.windowHeight(), LENGTH_CANDS, belowCeiling);
            scalar(DOOR_WIDTH, ranges.doorWidth(), LENGTH_CANDS, withinWall);
            scalar(WINDOW_WIDTH, ranges.windowWidth(), LENGTH_CANDS, withinWall);
            scalar(NARROW_PLACE, ranges.narrowPlace(), LENGTH_CANDS, c -> true);
            scalar(PERIMETER, ranges.perimeter(), LENGTH_CANDS, c -> true);

            double openingsArea = Math.max(areaHint(DOOR_AREA), areaHint(WINDOW_AREA));
            scalar(WALL_AREA, ranges.wallArea(), AREA_CANDS, c -> c >= openingsArea);

            Double wallArea = value(WALL_AREA);
            Range openingArea = new Range(0, wallArea != null ? wallArea : ranges.wallArea().max());
            scalar(DOOR_AREA, openingArea, AREA_CANDS, c -> true);
            scalar(WINDOW_AREA, openingArea, AREA_CANDS, c -> true);

            Double polygon = polygonArea();
            scalar(FLOOR_AREA, ranges.floorArea(), AREA_CANDS,
                    c -> polygon == null || Math.abs(c - polygon) <= areaTolerance * polygon);

            scalar(DOOR_COUNT, ranges.count(), NO_CANDS, c -> true);
            scalar(WINDOW_COUNT, ranges.count(), NO_CANDS, c -> true);
        }

        private double areaHint(String field) {
            Double a = value(field);
            return a != null && a >= 0 && a <= ranges.wallArea().max() ? a : Double.NEGATIVE_INFINITY;
        }

        private Double polygonArea() {
            if (geometry == null || geometry.getVertices() == null || geometry.getVertices().size() < 3
                    || geometry.getVertices().stream().anyMatch(v -> v == null)) {
                return null;
            }
            double a = metrics(geometry.getVertices())[1];
            return Double.isFinite(a) && a > 0 ? a : null;
        }

        private void scalar(String field, Range range, List<Cand> cands, DoublePredicate cross) {
            Double v = value(field);
            if (v == null) {
                return;
            }
            set(field, check(field, v, unit(field), range, cands, cross));
        }

        // ---- shared ---------------------------------------------------------------------------

        private Double value(String field) {
            Quantity q = values.get(field);
            return q == null ? null : q.value();
        }

        private String unit(String field) {
            Quantity q = values.get(field);
            return q == null ? null : q.unit();
        }

        private void set(String field, Double v) {
            values.put(field, new Quantity(v, unit(field)));
        }

        /** Returns the (possibly corrected) value, or {@code null} when it has to be cleared. */
        private Double check(String field, double v, String unit, Range range, List<Cand> cands,
                             DoublePredicate cross) {
            if (range.contains(v)) {
                return v;
            }
            List<Cand> fits = new ArrayList<>();
            for (Cand c : cands) {
                double x = c.apply(v);
                if (range.contains(x) && cross.test(x)) {
                    fits.add(c);
                }
            }
            if (fits.size() == 1) {
                Cand c = fits.get(0);
                double corrected = c.apply(v);
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("field", field);
                params.put("original", v);
                params.put("originalUnit", unit);
                params.put("corrected", corrected);
                params.put("factor", c.factor());
                Map<String, Object> fix = new LinkedHashMap<>();
                fix.put(field, v);
                warnings.add(new Warning(UNIT_CORRECTED, Severity.INFO, in.roomTempId(), field, params, fix));
                return corrected;
            }
            outOfRange(field, v, unit, range);
            return null;
        }

        private void outOfRange(String field, double original, String unit, Range range) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("field", field);
            params.put("original", original);
            params.put("originalUnit", unit);
            params.put("min", range.min());
            params.put("max", range.max());
            warnings.add(new Warning(WarningCodes.DIMENSION_OUT_OF_RANGE, Severity.WARNING, in.roomTempId(),
                    field, params, null));
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** {@code {maxBoundingBoxSide, absShoelaceArea}} of the outline scaled by {@code scale}. */
    private static double[] metrics(List<RoomGeometry.Vertex> vs) {
        return metrics(vs, new Cand(1.0, false));
    }

    private static double[] metrics(List<RoomGeometry.Vertex> vs, Cand c) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double twice = 0;
        int n = vs.size();
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = c.apply(vs.get(i).getX());
            ys[i] = c.apply(vs.get(i).getY());
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            twice += xs[i] * ys[j] - xs[j] * ys[i];
        }
        return new double[] {Math.max(maxX - minX, maxY - minY), Math.abs(twice) / 2.0};
    }

    private static RoomGeometry copy(RoomGeometry g) {
        if (g == null) {
            return null;
        }
        RoomGeometry r = new RoomGeometry();
        if (g.getVertices() != null) {
            List<RoomGeometry.Vertex> vs = new ArrayList<>();
            for (RoomGeometry.Vertex v : g.getVertices()) {
                if (v == null) {
                    vs.add(null);
                    continue;
                }
                RoomGeometry.Vertex nv = new RoomGeometry.Vertex();
                nv.setX(v.getX());
                nv.setY(v.getY());
                vs.add(nv);
            }
            r.setVertices(vs);
        }
        if (g.getWalls() != null) {
            List<RoomGeometry.Wall> ws = new ArrayList<>();
            for (RoomGeometry.Wall w : g.getWalls()) {
                if (w == null) {
                    ws.add(null);
                    continue;
                }
                RoomGeometry.Wall nw = new RoomGeometry.Wall();
                nw.setWallGap(w.getWallGap());
                nw.setFinishGap(w.getFinishGap());
                if (w.getOpenings() != null) {
                    List<RoomGeometry.Opening> os = new ArrayList<>();
                    for (RoomGeometry.Opening o : w.getOpenings()) {
                        if (o == null) {
                            os.add(null);
                            continue;
                        }
                        RoomGeometry.Opening no = new RoomGeometry.Opening();
                        no.setType(o.getType());
                        no.setCount(o.getCount());
                        no.setHeight(o.getHeight());
                        no.setWidth(o.getWidth());
                        no.setOffset(o.getOffset());
                        os.add(no);
                    }
                    nw.setOpenings(os);
                }
                ws.add(nw);
            }
            r.setWalls(ws);
        }
        return r;
    }
}
