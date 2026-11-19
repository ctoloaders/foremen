package com.foremen.service;

import com.foremen.dao.model.MeasureSource;
import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.model.MetricExplanation;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * FOR-05-02 Round 11 #3 — builds the formula explanation of the CALCULATED room metrics so the client
 * can show "how it was calculated" without duplicating the calculation logic. It mirrors
 * {@link RoomCalculationService} and the matrix derivation:
 * <ul>
 *   <li>{@code doorArea}/{@code windowArea} = {@code count * height * width} (flat room) or the
 *       per-opening sum of constants (room with geometry, where openings differ);</li>
 *   <li>{@code wallArea} = {@code (perimeter - finishGap - wallGap) * ceilingHeight - doorArea - windowArea}.</li>
 * </ul>
 * Only metrics whose source is {@link MeasureSource#CALCULATED} are explained. Absent variables are
 * reported as {@code 0}, exactly as the calculation treats them.
 */
public final class RoomMetricExplainer {

    /** The formula of the calculated wall area. */
    public static final String WALL_AREA_FORMULA = "(perimeter - finishGap - wallGap) * ceilingHeight - doorArea - windowArea";

    private RoomMetricExplainer() {
    }

    /** Raw room values the explanation reads (a view over the service read models). */
    public record Input(
            RoomGeometry geometry,
            BigDecimal ceilingHeight,
            Integer doorCount, BigDecimal doorHeight, BigDecimal doorWidth,
            Integer windowCount, BigDecimal windowHeight, BigDecimal windowWidth,
            BigDecimal finishGap,
            BigDecimal wallGap,
            BigDecimal perimeter, BigDecimal doorArea, BigDecimal windowArea,
            MeasureSource wallAreaSource, MeasureSource doorAreaSource, MeasureSource windowAreaSource,
            MeasureSource perimeterSource, Map<String, String> dimensionFormulas) {
    }

    /**
     * Returns the explanation per metric field name ({@code wallArea}/{@code doorArea}/
     * {@code windowArea}), or {@code null} when no metric is calculated.
     */
    public static Map<String, MetricExplanation> explain(Input in) {
        Map<String, MetricExplanation> out = new LinkedHashMap<>();
        if (in.doorAreaSource() == MeasureSource.CALCULATED) {
            out.put("doorArea", openingArea(in, OpeningType.DOOR, "doorCount", "doorHeight", "doorWidth",
                    in.doorCount(), in.doorHeight(), in.doorWidth()));
        }
        if (in.windowAreaSource() == MeasureSource.CALCULATED) {
            out.put("windowArea", openingArea(in, OpeningType.WINDOW, "windowCount", "windowHeight", "windowWidth",
                    in.windowCount(), in.windowHeight(), in.windowWidth()));
        }
        if (in.perimeterSource() == MeasureSource.CALCULATED) {
            MetricExplanation perimeter = perimeter(in);
            if (perimeter != null) {
                out.put("perimeter", perimeter);
            }
        }
        if (in.wallAreaSource() == MeasureSource.CALCULATED) {
            Map<String, BigDecimal> vars = new LinkedHashMap<>();
            vars.put("perimeter", orZero(in.perimeter()));
            vars.put("finishGap", orZero(in.finishGap()));
            vars.put("wallGap", orZero(in.wallGap()));
            vars.put("ceilingHeight", orZero(in.ceilingHeight()));
            vars.put("doorArea", orZero(in.doorArea()));
            vars.put("windowArea", orZero(in.windowArea()));
            out.put("wallArea", new MetricExplanation(WALL_AREA_FORMULA, vars));
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * FOR-05-02c: perimeter as {@code wall1 + ... + wallN}; wall {@code i} runs from vertex {@code i} to
     * vertex {@code (i+1) mod n} and its variable is the length rounded to 2 decimals. A wall length
     * formula ({@code dimensionFormulas["geometry.walls[i].length"]}, without the leading {@code =})
     * replaces {@code wall<i>} as term and variable key. {@code null} for fewer than 2 vertices.
     */
    private static MetricExplanation perimeter(Input in) {
        List<RoomGeometry.Vertex> vertices = in.geometry() == null ? null : in.geometry().getVertices();
        int n = vertices == null ? 0 : vertices.size();
        if (n < 2) {
            return null;
        }
        StringJoiner terms = new StringJoiner(" + ");
        Map<String, BigDecimal> vars = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            RoomGeometry.Vertex a = vertices.get(i);
            RoomGeometry.Vertex b = vertices.get((i + 1) % n);
            if (a == null || b == null) {
                continue;
            }
            BigDecimal length = BigDecimal.valueOf(Math.hypot(b.getX() - a.getX(), b.getY() - a.getY()))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            String raw = in.dimensionFormulas() == null ? null
                    : in.dimensionFormulas().get("geometry.walls[" + i + "].length");
            String text = raw == null ? "" : raw.trim().replaceFirst("^=\\s*", "");
            String name = text.isEmpty() ? "wall" + (i + 1) : text;
            terms.add(name);
            vars.put(name, length);
        }
        return new MetricExplanation(terms.toString(), vars);
    }

    private static MetricExplanation openingArea(Input in, OpeningType type, String countVar, String heightVar,
                                                 String widthVar, Integer count, BigDecimal height,
                                                 BigDecimal width) {
        boolean hasGeometry = in.geometry() != null && in.geometry().getVertices() != null
                && !in.geometry().getVertices().isEmpty();
        if (hasGeometry) {
            List<RoomGeometry.Wall> walls = in.geometry().getWalls() == null ? List.of() : in.geometry().getWalls();
            StringJoiner sum = new StringJoiner(" + ");
            for (RoomGeometry.Wall wall : walls) {
                if (wall == null || wall.getOpenings() == null) {
                    continue;
                }
                for (RoomGeometry.Opening o : wall.getOpenings()) {
                    if (o != null && o.getType() == type) {
                        sum.add(o.getCount() + " * " + plain(o.getHeight()) + " * " + plain(o.getWidth()));
                    }
                }
            }
            return new MetricExplanation(sum.length() == 0 ? "0" : sum.toString(), new LinkedHashMap<>());
        }
        Map<String, BigDecimal> vars = new LinkedHashMap<>();
        vars.put(countVar, count == null ? BigDecimal.ZERO : BigDecimal.valueOf(count));
        vars.put(heightVar, orZero(height));
        vars.put(widthVar, orZero(width));
        return new MetricExplanation(countVar + " * " + heightVar + " * " + widthVar, vars);
    }

    private static String plain(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
