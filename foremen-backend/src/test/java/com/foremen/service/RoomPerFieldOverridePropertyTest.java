package com.foremen.service;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.model.MeasureSource;
import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.RoomMetrics;
import com.foremen.service.model.RoomServiceExtendedModel;
import com.foremen.service.model.mapper.RoomServiceMapper;
import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Property 4 (FOR-05-02): Per-field manual override of a geometry room.
 *
 * <p>Extends the FOR-04-14 "geometry authoritative" rule with a per-field override: for a room that
 * has a <em>present</em> geometry, an update that supplies an explicit value carrying a
 * {@code MANUAL} source for a subset K of the five metrics ({@code floorArea}, {@code wallArea},
 * {@code perimeter}, {@code doorArea}, {@code windowArea}) persists <b>exactly</b> those K fields as
 * {@code MANUAL} (with the supplied value preserved), while every other metric is still derived from
 * geometry and stamped {@code CALCULATED} (Requirements 5.2, 5.3, 5.4). The property draws a random
 * subset K (any of the 32 combinations, including the empty set and the full set) plus random
 * in-range override values and a random geometry, runs the update-path hook, and asserts the
 * per-field split.
 *
 * <p>The empty-K case degenerates to the FOR-04-14 all-{@code CALCULATED} behavior; the full-K case
 * flips all five to {@code MANUAL}. Between them every mixed override is exercised, so the property
 * pins down the exact "only the overridden fields flip" contract of Requirement 5.5.
 *
 * <p>As in {@link RoomGeometryAuthoritativePropertyTest}, the reference-resolution DAOs are
 * Mockito-mocked to resolve any id to a present entity so normalization proceeds, and the real
 * {@link RoomCalculationService} is used so the expected {@code CALCULATED} values are produced by
 * the same engine the service delegates to.
 *
 * <p>Feature: FOR-05-02-rooms-dimensions, Property 4
 *
 * <p><b>Validates: Requirements 5.2, 5.3, 5.4, 5.5</b>
 */
@Tag("Feature: FOR-05-02-rooms-dimensions, Property 4")
class RoomPerFieldOverridePropertyTest {

    /** Index into the five-metric arrays, in the order used throughout this test. */
    private static final int FLOOR = 0;
    private static final int WALL = 1;
    private static final int PERIMETER = 2;
    private static final int DOOR = 3;
    private static final int WINDOW = 4;

    private final RoomCalculationService calculationService = new RoomCalculationService();
    private final RoomService roomService = buildService(calculationService);

    // --- Property: exactly the overridden subset stays MANUAL, the rest CALCULATED ---

    @Property(tries = 100)
    void overridingSubsetKeepsExactlyThoseManualAndRestCalculated(
            @ForAll("simplePolygons") List<RoomGeometry.Vertex> vertices,
            @ForAll("wallLists") List<RoomGeometry.Wall> walls,
            @ForAll("optionalCeilingHeight") BigDecimal ceilingHeight,
            @ForAll("overrideMask") boolean[] override,
            @ForAll("overrideValues") BigDecimal[] overrideValue) {

        RoomGeometry geometry = new RoomGeometry();
        geometry.setVertices(vertices);
        geometry.setWalls(walls);

        RoomServiceExtendedModel model = new RoomServiceExtendedModel();
        model.setProjectId(1L);
        model.setRoomTypeId(2L);
        model.setGeometry(geometry);
        model.setCeilingHeight(ceilingHeight);

        // For each metric in K: supply an explicit value + MANUAL source (a per-field override).
        // For metrics NOT in K: leave value/source null so geometry derives them as CALCULATED.
        applyOverride(model::setFloorArea, model::setFloorAreaSource, override[FLOOR], overrideValue[FLOOR]);
        applyOverride(model::setWallArea, model::setWallAreaSource, override[WALL], overrideValue[WALL]);
        applyOverride(model::setPerimeter, model::setPerimeterSource, override[PERIMETER], overrideValue[PERIMETER]);
        applyOverride(model::setDoorArea, model::setDoorAreaSource, override[DOOR], overrideValue[DOOR]);
        applyOverride(model::setWindowArea, model::setWindowAreaSource, override[WINDOW], overrideValue[WINDOW]);

        // Independent reference: the same engine the service delegates to for the non-overridden fields.
        RoomMetrics expected = calculationService.calculate(geometry, ceilingHeight);

        roomService.validateUpdate(null, model);

        assertMetric(override[FLOOR], overrideValue[FLOOR], expected.floorArea(),
                model.getFloorArea(), model.getFloorAreaSource());
        assertMetric(override[WALL], overrideValue[WALL], expected.wallArea(),
                model.getWallArea(), model.getWallAreaSource());
        assertMetric(override[PERIMETER], overrideValue[PERIMETER], expected.perimeter(),
                model.getPerimeter(), model.getPerimeterSource());
        assertMetric(override[DOOR], overrideValue[DOOR], expected.doorArea(),
                model.getDoorArea(), model.getDoorAreaSource());
        assertMetric(override[WINDOW], overrideValue[WINDOW], expected.windowArea(),
                model.getWindowArea(), model.getWindowAreaSource());
    }

    /**
     * When {@code overridden}, sets the supplied manual value + {@code MANUAL} source; otherwise
     * leaves both {@code null} so the geometry path derives the field.
     */
    private void applyOverride(java.util.function.Consumer<BigDecimal> setValue,
                               java.util.function.Consumer<MeasureSource> setSource,
                               boolean overridden,
                               BigDecimal value) {
        if (overridden) {
            setValue.accept(value);
            setSource.accept(MeasureSource.MANUAL);
        } else {
            setValue.accept(null);
            setSource.accept(null);
        }
    }

    /**
     * Asserts a single metric obeys the per-field contract: an overridden field keeps the supplied
     * manual value and {@code MANUAL} source; a non-overridden field equals the engine's
     * geometry-derived value and is stamped {@code CALCULATED}.
     */
    private void assertMetric(boolean overridden, BigDecimal supplied, BigDecimal calculated,
                              BigDecimal actualValue, MeasureSource actualSource) {
        if (overridden) {
            assertThat(actualSource).isEqualTo(MeasureSource.MANUAL);
            assertThat(actualValue).isEqualByComparingTo(supplied);
        } else {
            assertThat(actualSource).isEqualTo(MeasureSource.CALCULATED);
            assertThat(actualValue).isEqualByComparingTo(calculated);
        }
    }

    // --- Fixture ---

    /**
     * Builds a {@link RoomService} whose reference DAOs resolve any id to a present entity (so
     * normalization proceeds past reference resolution) over the given real calculation engine. The
     * remaining collaborators are unused on the normalization path and are mocked.
     */
    private static RoomService buildService(RoomCalculationService calculationService) {
        ProjectDao projectDao = Mockito.mock(ProjectDao.class);
        RoomTypeDao roomTypeDao = Mockito.mock(RoomTypeDao.class);
        when(projectDao.findById(anyLong())).thenReturn(Optional.of(new ProjectEntity()));
        when(roomTypeDao.findById(anyLong())).thenReturn(Optional.of(new RoomTypeEntity()));

        return new RoomService(
                Mockito.mock(RoomDao.class),
                Mockito.mock(RoomServiceMapper.class),
                Mockito.mock(ProjectAccessCache.class),
                Mockito.mock(AuditLogDao.class),
                Mockito.mock(EntityManager.class),
                projectDao,
                roomTypeDao,
                calculationService);
    }

    // --- Providers ---

    /**
     * Generates simple polygons of 3..10 vertices by ordering unique random points by polar angle
     * around their centroid, mirroring the P1/P5 generators (a non-self-intersecting boundary).
     */
    @Provide
    Arbitrary<List<RoomGeometry.Vertex>> simplePolygons() {
        Arbitrary<Double> coordinate = Arbitraries.doubles().between(-100.0d, 100.0d);
        Arbitrary<Point> points = Combinators.combine(coordinate, coordinate).as(Point::new);
        return points.list().uniqueElements(p -> p.x + "," + p.y).ofMinSize(3).ofMaxSize(10)
                .map(this::toSimplePolygon)
                .filter(polygon -> polygon.size() >= 3);
    }

    /** Orders points by polar angle around the centroid to form a simple polygon. */
    private List<RoomGeometry.Vertex> toSimplePolygon(List<Point> points) {
        double cx = points.stream().mapToDouble(p -> p.x).average().orElse(0.0d);
        double cy = points.stream().mapToDouble(p -> p.y).average().orElse(0.0d);

        List<Point> ordered = new ArrayList<>(points);
        ordered.sort(Comparator.comparingDouble(p -> Math.atan2(p.y - cy, p.x - cx)));

        List<RoomGeometry.Vertex> vertices = new ArrayList<>(ordered.size());
        for (Point p : ordered) {
            RoomGeometry.Vertex vertex = new RoomGeometry.Vertex();
            vertex.setX(p.x);
            vertex.setY(p.y);
            vertices.add(vertex);
        }
        return vertices;
    }

    /** Generates 0..5 walls, each carrying optional gaps and 0..3 valid openings. */
    @Provide
    Arbitrary<List<RoomGeometry.Wall>> wallLists() {
        return walls().list().ofMinSize(0).ofMaxSize(5);
    }

    private Arbitrary<RoomGeometry.Wall> walls() {
        Arbitrary<Double> gap = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.doubles().between(0.0d, 10.0d));
        Arbitrary<List<RoomGeometry.Opening>> openings = openings().list().ofMinSize(0).ofMaxSize(3);
        return Combinators.combine(gap, gap, openings).as((wallGap, finishGap, ops) -> {
            RoomGeometry.Wall wall = new RoomGeometry.Wall();
            wall.setWallGap(wallGap);
            wall.setFinishGap(finishGap);
            wall.setOpenings(ops);
            return wall;
        });
    }

    private Arbitrary<RoomGeometry.Opening> openings() {
        Arbitrary<OpeningType> type = Arbitraries.of(OpeningType.DOOR, OpeningType.WINDOW);
        Arbitrary<Integer> count = Arbitraries.integers().between(1, 5);
        Arbitrary<Double> height = Arbitraries.doubles().between(0.1d, 5.0d);
        Arbitrary<Double> width = Arbitraries.doubles().between(0.1d, 5.0d);
        return Combinators.combine(type, count, height, width).as((t, c, h, w) -> {
            RoomGeometry.Opening opening = new RoomGeometry.Opening();
            opening.setType(t);
            opening.setCount(c);
            opening.setHeight(h);
            opening.setWidth(w);
            return opening;
        });
    }

    /** Ceiling height: either absent (null) or a value in {@code [0, 10]}. */
    @Provide
    Arbitrary<BigDecimal> optionalCeilingHeight() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.bigDecimals().between(BigDecimal.ZERO, BigDecimal.TEN).ofScale(2));
    }

    /**
     * The override subset K as five booleans (one per metric in FLOOR/WALL/PERIMETER/DOOR/WINDOW
     * order). Covers all 32 combinations including the empty set (all CALCULATED) and the full set
     * (all MANUAL).
     */
    @Provide
    Arbitrary<boolean[]> overrideMask() {
        Arbitrary<Boolean> flag = Arbitraries.of(true, false);
        return flag.list().ofSize(5).map(list -> {
            boolean[] mask = new boolean[5];
            for (int i = 0; i < 5; i++) {
                mask[i] = list.get(i);
            }
            return mask;
        });
    }

    /**
     * Five in-range override values ({@code [0, 9999999999.99]}), one per metric. Only the values at
     * positions set in {@link #overrideMask()} are actually applied to the model; the rest are unused.
     */
    @Provide
    Arbitrary<BigDecimal[]> overrideValues() {
        Arbitrary<BigDecimal> metric =
                Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("9999999999.99")).ofScale(2);
        return metric.array(BigDecimal[].class).ofSize(5);
    }

    /** Simple immutable coordinate pair used only for generation. */
    private record Point(double x, double y) {
    }
}
