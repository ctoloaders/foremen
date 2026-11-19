package com.foremen.service;

import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.model.RoomMetrics;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Deterministic, side-effect-free geometry calculation engine for rooms (FOR-04-14, Requirement 3).
 *
 * <p>Given a {@link RoomGeometry} and the room's ceiling height, {@link #calculate(RoomGeometry,
 * BigDecimal)} derives the geometry-authoritative metrics and returns them as an immutable
 * {@link RoomMetrics} result. The engine reads only its arguments and produces the same result for
 * the same input (Requirement 3.5); it mutates neither the geometry nor any external state.
 *
 * <p>Formulas:
 * <ul>
 *   <li>{@code floorArea} = |shoelace(vertices)| (Req 3.1)</li>
 *   <li>{@code perimeter} = Σ Euclidean edge lengths, wall {@code i} joining vertex {@code i} to
 *       vertex {@code (i + 1) mod n} (Req 3.2)</li>
 *   <li>{@code doorArea}/{@code windowArea} = Σ {@code count × height × width} per opening type;
 *       {@code doorCount}/{@code windowCount} = Σ counts (Req 3.4)</li>
 *   <li>{@code wallGap}/{@code finishGap} = Σ per-wall gap lengths (Req 2.5)</li>
 *   <li>{@code wallArea} = {@code max(0, (perimeter − finishGap − wallGap) × ceilingHeight − doorArea −
 *       windowArea)} (Req 3.3; FOR-05-02 Round 11 #2)</li>
 * </ul>
 *
 * <p>All {@code BigDecimal} metrics are rounded to 2 decimals ({@code NUMERIC(12,2)}) with
 * {@link RoundingMode#HALF_UP}.
 */
@Service
public class RoomCalculationService {

    /** Decimal scale of every persisted metric ({@code NUMERIC(12,2)}). */
    private static final int SCALE = 2;

    /** Rounding mode applied to every metric. */
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /**
     * Computes the geometry-derived metrics for the given room shape.
     *
     * @param geometry      the room polygon with per-wall openings; must not be {@code null}
     * @param ceilingHeight the room ceiling height (mb) used for the wall-area formula; when
     *                      {@code null} it is treated as zero, yielding a wall area of zero
     * @return the derived metrics, each rounded to 2 decimals ({@code HALF_UP})
     */
    public RoomMetrics calculate(RoomGeometry geometry, BigDecimal ceilingHeight) {
        List<RoomGeometry.Vertex> vertices =
                geometry.getVertices() == null ? List.of() : geometry.getVertices();
        List<RoomGeometry.Wall> walls =
                geometry.getWalls() == null ? List.of() : geometry.getWalls();

        BigDecimal floorArea = round(shoelaceArea(vertices));
        BigDecimal perimeter = round(perimeter(vertices));

        WallTotals totals = aggregateWalls(walls);

        BigDecimal doorArea = round(BigDecimal.valueOf(totals.doorAreaRaw));
        BigDecimal windowArea = round(BigDecimal.valueOf(totals.windowAreaRaw));
        BigDecimal wallGap = round(BigDecimal.valueOf(totals.wallGapRaw));
        BigDecimal finishGap = round(BigDecimal.valueOf(totals.finishGapRaw));

        BigDecimal height = ceilingHeight == null ? BigDecimal.ZERO : ceilingHeight;
        BigDecimal wallArea = round(wallAreaRaw(perimeter, height, doorArea, windowArea, finishGap, wallGap));

        return new RoomMetrics(
                floorArea,
                perimeter,
                wallArea,
                doorArea,
                windowArea,
                totals.doorCount,
                totals.windowCount,
                wallGap,
                finishGap);
    }

    /**
     * Sums the per-wall gap lengths and per-type opening areas/counts across all walls.
     * {@code null} walls and openings, and openings without a recognised {@link OpeningType},
     * are skipped.
     */
    private WallTotals aggregateWalls(List<RoomGeometry.Wall> walls) {
        WallTotals totals = new WallTotals();
        for (RoomGeometry.Wall wall : walls) {
            if (wall == null) {
                continue;
            }
            if (wall.getWallGap() != null) {
                totals.wallGapRaw += wall.getWallGap();
            }
            if (wall.getFinishGap() != null) {
                totals.finishGapRaw += wall.getFinishGap();
            }
            accumulateOpenings(wall.getOpenings(), totals);
        }
        return totals;
    }

    /** Accumulates a single wall's openings into {@code totals}, split by opening type. */
    private void accumulateOpenings(List<RoomGeometry.Opening> openings, WallTotals totals) {
        if (openings == null) {
            return;
        }
        for (RoomGeometry.Opening opening : openings) {
            if (opening == null || opening.getType() == null) {
                continue;
            }
            double area = opening.getCount() * opening.getHeight() * opening.getWidth();
            if (opening.getType() == OpeningType.DOOR) {
                totals.doorAreaRaw += area;
                totals.doorCount += opening.getCount();
            } else if (opening.getType() == OpeningType.WINDOW) {
                totals.windowAreaRaw += area;
                totals.windowCount += opening.getCount();
            }
        }
    }

    /** Mutable accumulator for the per-wall aggregation, kept in raw {@code double} units. */
    private static final class WallTotals {
        private double doorAreaRaw;
        private double windowAreaRaw;
        private int doorCount;
        private int windowCount;
        private double wallGapRaw;
        private double finishGapRaw;
    }

    /**
     * Absolute polygon area via the shoelace formula:
     * {@code |Σ (x_i · y_{i+1} − x_{i+1} · y_i)| / 2}. Returns zero for degenerate input
     * (fewer than 3 vertices).
     */
    private BigDecimal shoelaceArea(List<RoomGeometry.Vertex> vertices) {
        int n = vertices.size();
        if (n < 3) {
            return BigDecimal.ZERO;
        }
        double sum = 0.0d;
        for (int i = 0; i < n; i++) {
            RoomGeometry.Vertex current = vertices.get(i);
            RoomGeometry.Vertex next = vertices.get((i + 1) % n);
            sum += (current.getX() * next.getY()) - (next.getX() * current.getY());
        }
        return BigDecimal.valueOf(Math.abs(sum) / 2.0d);
    }

    /**
     * Perimeter as the sum of Euclidean edge lengths, wall {@code i} joining vertex {@code i} to
     * vertex {@code (i + 1) mod n}. Returns zero for fewer than 2 vertices.
     */
    private BigDecimal perimeter(List<RoomGeometry.Vertex> vertices) {
        int n = vertices.size();
        if (n < 2) {
            return BigDecimal.ZERO;
        }
        double sum = 0.0d;
        for (int i = 0; i < n; i++) {
            RoomGeometry.Vertex current = vertices.get(i);
            RoomGeometry.Vertex next = vertices.get((i + 1) % n);
            double dx = next.getX() - current.getX();
            double dy = next.getY() - current.getY();
            sum += Math.hypot(dx, dy);
        }
        return BigDecimal.valueOf(sum);
    }

    /**
     * {@code max(0, (perimeter − finishGap − wallGap) × height − doorArea − windowArea)} (Req 3.3;
     * FOR-05-02 Round 11 #2: the missing finish and missing wall lengths are taken off the perimeter), computed on the
     * rounded components before the final rounding.
     */
    private BigDecimal wallAreaRaw(BigDecimal perimeter, BigDecimal height, BigDecimal doorArea,
                                BigDecimal windowArea, BigDecimal finishGap, BigDecimal wallGap) {
        BigDecimal gross = perimeter.subtract(finishGap).subtract(wallGap).multiply(height);
        BigDecimal net = gross.subtract(doorArea).subtract(windowArea);
        return net.max(BigDecimal.ZERO);
    }

    /**
     * Reusable wall-area formula (FOR-05-02c): {@code max(0, (perimeter − finishGap − wallGap) ×
     * ceilingHeight − doorArea − windowArea)}, rounded to 2 decimals like {@link #calculate}. A
     * {@code null} ceiling height is treated as zero; {@code null} gaps/areas as zero.
     */
    public BigDecimal wallArea(BigDecimal perimeter, BigDecimal ceilingHeight, BigDecimal doorArea,
                               BigDecimal windowArea, BigDecimal finishGap, BigDecimal wallGap) {
        return round(wallAreaRaw(
                nz(perimeter),
                nz(ceilingHeight),
                nz(doorArea),
                nz(windowArea),
                nz(finishGap),
                nz(wallGap)));
    }

    /** Height and width of a single opening (mb). */
    public record OpeningSize(BigDecimal height, BigDecimal width) {
    }

    /**
     * Size of the only opening of {@code type}: present only when the sum of {@code count} over
     * the openings of that type is exactly 1 (FOR-05-02c, Req 2.5/2.6); otherwise empty.
     */
    public java.util.Optional<OpeningSize> openingSize(RoomGeometry geometry, OpeningType type) {
        if (geometry == null || geometry.getWalls() == null) {
            return java.util.Optional.empty();
        }
        int total = 0;
        RoomGeometry.Opening single = null;
        for (RoomGeometry.Wall wall : geometry.getWalls()) {
            if (wall == null || wall.getOpenings() == null) {
                continue;
            }
            for (RoomGeometry.Opening opening : wall.getOpenings()) {
                if (opening == null || opening.getType() != type) {
                    continue;
                }
                total += opening.getCount();
                single = opening;
            }
        }
        if (total != 1 || single == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new OpeningSize(
                round(BigDecimal.valueOf(single.getHeight())),
                round(BigDecimal.valueOf(single.getWidth()))));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal round(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }
}
