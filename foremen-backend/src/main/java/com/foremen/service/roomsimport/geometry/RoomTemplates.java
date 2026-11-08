package com.foremen.service.roomsimport.geometry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Room outline templates: exact Java port of the frontend {@code roomTemplates.ts} (FOR-05-02
 * autoimport Round 2 / rooms-dimensions Round 9). Each template builds a closed, counter-clockwise
 * (y-up) outline that starts at (0,0) with its first edge along +x. Shared parity fixture:
 * {@code src/test/resources/roomsimport/room-templates.json} (copy of the frontend
 * {@code __tests__/fixtures/room-templates.json}).
 *
 * <p>ROTATION CONVENTION ({@link #rotateOutline}, identical to the frontend):
 * <ol>
 *   <li>Coordinates are y-up (x right, y up). The input is a counter-clockwise polygon.</li>
 *   <li>One clockwise quarter turn maps (x, y) -> (y, -x); k turns apply it k times
 *       (k = 0 is the identity, 2 is (x, y) -> (-x, -y), 3 is (x, y) -> (-y, x)).</li>
 *   <li>The outline is translated so that min x = 0 and min y = 0.</li>
 *   <li>The vertex order stays counter-clockwise (rotation preserves orientation).</li>
 *   <li>The list is cyclically re-started at the vertex with the smallest y, ties broken by the
 *       smallest x (also for k = 0). Wall 0 is the outgoing edge of that vertex.</li>
 *   <li>Every coordinate is rounded to 6 decimals (-0 becomes 0); the start vertex is picked after
 *       rounding.</li>
 * </ol>
 */
public final class RoomTemplates {

    public static final String ERR_POSITIVE = "rooms.form.templates.errors.positive";
    public static final String ERR_CUT = "rooms.form.templates.errors.cutTooLarge";

    /** The six templates, in frontend order. */
    public enum TemplateId {
        RECTANGLE, L_SHAPE, CUT_CORNER, T_SHAPE, U_SHAPE, CHAMFERED;

        public static Optional<TemplateId> parse(String s) {
            if (s == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(s.trim()));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    /** A point of an outline (metres, y-up). */
    public record Pt(double x, double y) {}

    /** A dimension key with its default value. */
    public record Dim(String key, double defaultValue) {}

    /** One template: dimension keys/defaults, validation (i18n key or null) and outline builder. */
    public record Template(TemplateId id, List<Dim> dims, Function<Map<String, Double>, String> validator,
                           Function<Map<String, Double>, List<Pt>> builder) {

        /** @return an i18n error key, or {@code null} when the dims are valid. */
        public String validate(Map<String, Double> d) {
            return validator.apply(full(d));
        }

        public List<Pt> build(Map<String, Double> d) {
            return builder.apply(full(d));
        }

        public List<String> dimKeys() {
            return dims.stream().map(Dim::key).toList();
        }

        public Map<String, Double> defaultDims() {
            Map<String, Double> m = new LinkedHashMap<>();
            dims.forEach(x -> m.put(x.key(), x.defaultValue()));
            return m;
        }
    }

    private static final List<String> KEYS = List.of("width", "length", "cutWidth", "cutLength", "cutSize",
            "stemWidth", "stemDepth", "topDepth", "notchWidth", "notchDepth", "chamferSize");

    /** Fully populated dims (missing keys read as 0). */
    private static Map<String, Double> full(Map<String, Double> d) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String k : KEYS) {
            Double v = d == null ? null : d.get(k);
            m.put(k, v == null ? 0.0 : v);
        }
        return m;
    }

    /** Round away float noise (1e-9 m), like the frontend {@code r}. */
    private static double r(double n) {
        return Math.round(n * 1e9) / 1e9;
    }

    private static Pt v(double x, double y) {
        return new Pt(r(x), r(y));
    }

    private static boolean allPositive(Map<String, Double> d, String... keys) {
        for (String k : keys) {
            double x = d.get(k);
            if (!Double.isFinite(x) || !(x > 0)) {
                return false;
            }
        }
        return true;
    }

    private static final List<Template> TEMPLATES = List.of(
            new Template(TemplateId.RECTANGLE, List.of(new Dim("width", 4), new Dim("length", 3)),
                    d -> allPositive(d, "width", "length") ? null : ERR_POSITIVE,
                    d -> List.of(v(0, 0), v(d.get("width"), 0), v(d.get("width"), d.get("length")),
                            v(0, d.get("length")))),
            new Template(TemplateId.L_SHAPE,
                    List.of(new Dim("width", 5), new Dim("length", 4), new Dim("cutWidth", 2),
                            new Dim("cutLength", 2)),
                    d -> {
                        if (!allPositive(d, "width", "length", "cutWidth", "cutLength")) {
                            return ERR_POSITIVE;
                        }
                        return d.get("cutWidth") < d.get("width") && d.get("cutLength") < d.get("length")
                                ? null : ERR_CUT;
                    },
                    // Cut out of the top-right corner.
                    d -> {
                        double w = d.get("width");
                        double l = d.get("length");
                        double cw = d.get("cutWidth");
                        double cl = d.get("cutLength");
                        return List.of(v(0, 0), v(w, 0), v(w, l - cl), v(w - cw, l - cl), v(w - cw, l), v(0, l));
                    }),
            new Template(TemplateId.CUT_CORNER,
                    List.of(new Dim("width", 4), new Dim("length", 3), new Dim("cutSize", 1)),
                    d -> {
                        if (!allPositive(d, "width", "length", "cutSize")) {
                            return ERR_POSITIVE;
                        }
                        return d.get("cutSize") < d.get("width") && d.get("cutSize") < d.get("length")
                                ? null : ERR_CUT;
                    },
                    // Top-right corner cut diagonally.
                    d -> {
                        double w = d.get("width");
                        double l = d.get("length");
                        double c = d.get("cutSize");
                        return List.of(v(0, 0), v(w, 0), v(w, l - c), v(w - c, l), v(0, l));
                    }),
            new Template(TemplateId.T_SHAPE,
                    // width = width of the wide top part; the narrower stem is centred at the bottom.
                    List.of(new Dim("width", 5), new Dim("topDepth", 2.5), new Dim("stemWidth", 2),
                            new Dim("stemDepth", 1.5)),
                    d -> {
                        if (!allPositive(d, "width", "topDepth", "stemWidth", "stemDepth")) {
                            return ERR_POSITIVE;
                        }
                        return d.get("stemWidth") < d.get("width") ? null : ERR_CUT;
                    },
                    // Starts at the stem's bottom-left corner (0,0); the wide part overhangs to x < 0.
                    d -> {
                        double o = (d.get("width") - d.get("stemWidth")) / 2;
                        double sw = d.get("stemWidth");
                        double sd = d.get("stemDepth");
                        double td = d.get("topDepth");
                        return List.of(v(0, 0), v(sw, 0), v(sw, sd), v(sw + o, sd), v(sw + o, sd + td),
                                v(-o, sd + td), v(-o, sd), v(0, sd));
                    }),
            new Template(TemplateId.U_SHAPE,
                    List.of(new Dim("width", 5), new Dim("length", 4), new Dim("notchWidth", 2),
                            new Dim("notchDepth", 1)),
                    d -> {
                        if (!allPositive(d, "width", "length", "notchWidth", "notchDepth")) {
                            return ERR_POSITIVE;
                        }
                        return d.get("notchWidth") < d.get("width") && d.get("notchDepth") < d.get("length")
                                ? null : ERR_CUT;
                    },
                    // Notch centred on the top edge.
                    d -> {
                        double w = d.get("width");
                        double l = d.get("length");
                        double nd = d.get("notchDepth");
                        double nx = (w - d.get("notchWidth")) / 2;
                        return List.of(v(0, 0), v(w, 0), v(w, l), v(w - nx, l), v(w - nx, l - nd),
                                v(nx, l - nd), v(nx, l), v(0, l));
                    }),
            new Template(TemplateId.CHAMFERED,
                    List.of(new Dim("width", 5), new Dim("length", 4), new Dim("chamferSize", 1)),
                    d -> {
                        if (!allPositive(d, "width", "length", "chamferSize")) {
                            return ERR_POSITIVE;
                        }
                        // Two chamfers share the top edge, so they must leave a non-empty flat part.
                        return 2 * d.get("chamferSize") < d.get("width") && d.get("chamferSize") < d.get("length")
                                ? null : ERR_CUT;
                    },
                    // Top two corners chamfered.
                    d -> {
                        double w = d.get("width");
                        double l = d.get("length");
                        double c = d.get("chamferSize");
                        return List.of(v(0, 0), v(w, 0), v(w, l - c), v(w - c, l), v(c, l), v(0, l - c));
                    }));

    private RoomTemplates() {}

    public static List<Template> all() {
        return TEMPLATES;
    }

    public static Template get(TemplateId id) {
        return TEMPLATES.stream().filter(t -> t.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown room template " + id));
    }

    /** Number of walls (= vertices) of the template outline. */
    public static int wallCount(TemplateId id) {
        return get(id).build(get(id).defaultDims()).size();
    }

    private static double snap6(double n) {
        double s = Math.round(n * 1e6) / 1e6;
        return s == 0 ? 0 : s; // also folds -0 into 0
    }

    /**
     * Rotates a counter-clockwise outline clockwise by {@code quarterTurnsClockwise} x 90 degrees, see the
     * class header for the exact convention.
     */
    public static List<Pt> rotateOutline(List<Pt> vertices, int quarterTurnsClockwise) {
        if (vertices.isEmpty()) {
            return List.of();
        }
        int turns = Math.floorMod(quarterTurnsClockwise, 4);
        List<Pt> pts = new ArrayList<>(vertices);
        for (int i = 0; i < turns; i++) {
            List<Pt> next = new ArrayList<>(pts.size());
            for (Pt p : pts) {
                next.add(new Pt(p.y(), -p.x()));
            }
            pts = next;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        for (Pt p : pts) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
        }
        List<Pt> snapped = new ArrayList<>(pts.size());
        for (Pt p : pts) {
            snapped.add(new Pt(snap6(p.x() - minX), snap6(p.y() - minY)));
        }
        int start = 0;
        for (int i = 1; i < snapped.size(); i++) {
            Pt a = snapped.get(i);
            Pt b = snapped.get(start);
            if (a.y() < b.y() || (a.y() == b.y() && a.x() < b.x())) {
                start = i;
            }
        }
        List<Pt> out = new ArrayList<>(snapped.size());
        out.addAll(snapped.subList(start, snapped.size()));
        out.addAll(snapped.subList(0, start));
        return List.copyOf(out);
    }

    /** Builds the template outline for {@code dims} and rotates it. */
    public static List<Pt> build(TemplateId id, Map<String, Double> dims, int quarterTurns) {
        return rotateOutline(get(id).build(dims), quarterTurns);
    }
}
