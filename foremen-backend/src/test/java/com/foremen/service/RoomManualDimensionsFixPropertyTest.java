package com.foremen.service;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.model.MeasureSource;
import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.geometry.PolygonChecks;
import com.foremen.service.model.MetricExplanation;
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
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * FOR-05-02c fix check (Properties 1, 2, 3): on the geometry path of {@code RoomService} a manual
 * root {@code wallGap}/{@code finishGap}/door-window size wins and is stamped {@code MANUAL};
 * otherwise gaps are the per-wall sums and sizes are the single opening's size (else {@code null}),
 * stamped {@code CALCULATED}. {@code wallArea} uses the effective gaps, areas stay summed over the
 * openings, clearing a manual value returns the field to {@code CALCULATED}, out-of-range manual
 * values are rejected, and the perimeter explanation lists every wall length.
 *
 * <p>Per field the generator picks one of four modes: 0 absent, 1 stale value with source
 * {@code CALCULATED}, 2 manual value with source {@code MANUAL}, 3 cleared (source {@code MANUAL}
 * but value {@code null}).
 *
 * <p><b>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 3.8</b>
 */
@Tag("Feature: FOR-05-02c-bugfix-room-manual-dimensions, Properties 1-3")
class RoomManualDimensionsFixPropertyTest {

    private static final int WALL_GAP = 0;
    private static final int FINISH_GAP = 1;
    private static final int DOOR_HEIGHT = 2;
    private static final int DOOR_WIDTH = 3;
    private static final int WINDOW_HEIGHT = 4;
    private static final int WINDOW_WIDTH = 5;

    private static final BigDecimal STALE = new BigDecimal("77.77");

    private final RoomCalculationService calculationService = new RoomCalculationService();
    private final RoomService roomService = buildService(calculationService);

    // --- Properties 1 and 2: manual wins, otherwise geometry-derived ---

    @Property(tries = 200)
    void manualWinsOtherwiseGeometryDerived(
            @ForAll("simplePolygons") List<RoomGeometry.Vertex> vertices,
            @ForAll("wallLists") List<RoomGeometry.Wall> walls,
            @ForAll("ceilingHeight") BigDecimal ceilingHeight,
            @ForAll("modes") List<Integer> modes,
            @ForAll("manualValues") List<BigDecimal> manual) {

        RoomGeometry geometry = new RoomGeometry();
        geometry.setVertices(vertices);
        geometry.setWalls(fitWalls(walls, vertices.size()));

        RoomServiceExtendedModel model = new RoomServiceExtendedModel();
        model.setProjectId(1L);
        model.setRoomTypeId(2L);
        model.setGeometry(geometry);
        model.setCeilingHeight(ceilingHeight);
        apply(model::setWallGap, model::setWallGapSource, modes.get(WALL_GAP), manual.get(WALL_GAP));
        apply(model::setFinishGap, model::setFinishGapSource, modes.get(FINISH_GAP), manual.get(FINISH_GAP));
        apply(model::setDoorHeight, model::setDoorHeightSource, modes.get(DOOR_HEIGHT), manual.get(DOOR_HEIGHT));
        apply(model::setDoorWidth, model::setDoorWidthSource, modes.get(DOOR_WIDTH), manual.get(DOOR_WIDTH));
        apply(model::setWindowHeight, model::setWindowHeightSource, modes.get(WINDOW_HEIGHT),
                manual.get(WINDOW_HEIGHT));
        apply(model::setWindowWidth, model::setWindowWidthSource, modes.get(WINDOW_WIDTH),
                manual.get(WINDOW_WIDTH));

        RoomMetrics expected = calculationService.calculate(geometry, ceilingHeight);

        roomService.validateUpdate(null, model);

        // Gaps: manual value kept and MANUAL, else per-wall sum and CALCULATED.
        BigDecimal effectiveWallGap = expectedValue(modes.get(WALL_GAP), manual.get(WALL_GAP), expected.wallGap());
        BigDecimal effectiveFinishGap =
                expectedValue(modes.get(FINISH_GAP), manual.get(FINISH_GAP), expected.finishGap());
        assertField(model.getWallGap(), model.getWallGapSource(), modes.get(WALL_GAP), manual.get(WALL_GAP),
                expected.wallGap());
        assertField(model.getFinishGap(), model.getFinishGapSource(), modes.get(FINISH_GAP),
                manual.get(FINISH_GAP), expected.finishGap());

        // wallArea (not overridden here) comes from the effective gaps; areas stay geometry sums.
        assertThat(model.getWallAreaSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(model.getWallArea()).isEqualByComparingTo(calculationService.wallArea(
                expected.perimeter(), ceilingHeight, expected.doorArea(), expected.windowArea(),
                effectiveFinishGap, effectiveWallGap));
        assertThat(model.getDoorArea()).isEqualByComparingTo(expected.doorArea());
        assertThat(model.getWindowArea()).isEqualByComparingTo(expected.windowArea());
        assertThat(model.getPerimeter()).isEqualByComparingTo(expected.perimeter());
        assertThat(model.getDoorCount()).isEqualTo(expected.doorCount());
        assertThat(model.getWindowCount()).isEqualTo(expected.windowCount());

        // Sizes: manual kept, else the single opening's size or null.
        Opening door = single(geometry, OpeningType.DOOR);
        Opening window = single(geometry, OpeningType.WINDOW);
        assertField(model.getDoorHeight(), model.getDoorHeightSource(), modes.get(DOOR_HEIGHT),
                manual.get(DOOR_HEIGHT), door == null ? null : door.height());
        assertField(model.getDoorWidth(), model.getDoorWidthSource(), modes.get(DOOR_WIDTH),
                manual.get(DOOR_WIDTH), door == null ? null : door.width());
        assertField(model.getWindowHeight(), model.getWindowHeightSource(), modes.get(WINDOW_HEIGHT),
                manual.get(WINDOW_HEIGHT), window == null ? null : window.height());
        assertField(model.getWindowWidth(), model.getWindowWidthSource(), modes.get(WINDOW_WIDTH),
                manual.get(WINDOW_WIDTH), window == null ? null : window.width());
    }

    /** A second save of the persisted state (values + sources) keeps every value and source. */
    @Property(tries = 100)
    void secondSaveKeepsPersistedState(
            @ForAll("simplePolygons") List<RoomGeometry.Vertex> vertices,
            @ForAll("wallLists") List<RoomGeometry.Wall> walls,
            @ForAll("ceilingHeight") BigDecimal ceilingHeight,
            @ForAll("modes") List<Integer> modes,
            @ForAll("manualValues") List<BigDecimal> manual) {

        RoomGeometry geometry = new RoomGeometry();
        geometry.setVertices(vertices);
        geometry.setWalls(fitWalls(walls, vertices.size()));

        RoomServiceExtendedModel first = new RoomServiceExtendedModel();
        first.setProjectId(1L);
        first.setRoomTypeId(2L);
        first.setGeometry(geometry);
        first.setCeilingHeight(ceilingHeight);
        apply(first::setWallGap, first::setWallGapSource, modes.get(WALL_GAP), manual.get(WALL_GAP));
        apply(first::setFinishGap, first::setFinishGapSource, modes.get(FINISH_GAP), manual.get(FINISH_GAP));
        apply(first::setDoorHeight, first::setDoorHeightSource, modes.get(DOOR_HEIGHT), manual.get(DOOR_HEIGHT));
        apply(first::setDoorWidth, first::setDoorWidthSource, modes.get(DOOR_WIDTH), manual.get(DOOR_WIDTH));
        roomService.validateUpdate(null, first);

        RoomServiceExtendedModel second = new RoomServiceExtendedModel();
        second.setProjectId(1L);
        second.setRoomTypeId(2L);
        second.setGeometry(geometry);
        second.setCeilingHeight(ceilingHeight);
        second.setWallGap(first.getWallGap());
        second.setWallGapSource(first.getWallGapSource());
        second.setFinishGap(first.getFinishGap());
        second.setFinishGapSource(first.getFinishGapSource());
        second.setDoorHeight(first.getDoorHeight());
        second.setDoorHeightSource(first.getDoorHeightSource());
        second.setDoorWidth(first.getDoorWidth());
        second.setDoorWidthSource(first.getDoorWidthSource());
        roomService.validateUpdate(null, second);

        assertSame(first.getWallGap(), second.getWallGap());
        assertSame(first.getFinishGap(), second.getFinishGap());
        assertSame(first.getDoorHeight(), second.getDoorHeight());
        assertSame(first.getDoorWidth(), second.getDoorWidth());
        assertThat(second.getWallGapSource()).isEqualTo(first.getWallGapSource());
        assertThat(second.getFinishGapSource()).isEqualTo(first.getFinishGapSource());
        assertThat(second.getDoorHeightSource()).isEqualTo(first.getDoorHeightSource());
        assertThat(second.getDoorWidthSource()).isEqualTo(first.getDoorWidthSource());
        assertThat(second.getWallArea()).isEqualByComparingTo(first.getWallArea());
    }

    // --- Req 3.8: out-of-range manual values are rejected ---

    @Property(tries = 60)
    void outOfRangeManualValueIsRejected(
            @ForAll("simplePolygons") List<RoomGeometry.Vertex> vertices,
            @ForAll("wallLists") List<RoomGeometry.Wall> walls,
            @ForAll("fieldIndex") int field,
            @ForAll("badValue") BigDecimal bad) {

        RoomGeometry geometry = new RoomGeometry();
        geometry.setVertices(vertices);
        geometry.setWalls(fitWalls(walls, vertices.size()));
        RoomServiceExtendedModel model = new RoomServiceExtendedModel();
        model.setProjectId(1L);
        model.setRoomTypeId(2L);
        model.setGeometry(geometry);
        switch (field) {
            case WALL_GAP -> { model.setWallGap(bad); model.setWallGapSource(MeasureSource.MANUAL); }
            case FINISH_GAP -> { model.setFinishGap(bad); model.setFinishGapSource(MeasureSource.MANUAL); }
            case DOOR_HEIGHT -> { model.setDoorHeight(bad); model.setDoorHeightSource(MeasureSource.MANUAL); }
            case DOOR_WIDTH -> { model.setDoorWidth(bad); model.setDoorWidthSource(MeasureSource.MANUAL); }
            case WINDOW_HEIGHT -> { model.setWindowHeight(bad); model.setWindowHeightSource(MeasureSource.MANUAL); }
            default -> { model.setWindowWidth(bad); model.setWindowWidthSource(MeasureSource.MANUAL); }
        }

        assertThatThrownBy(() -> roomService.validateUpdate(null, model))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(RoomService.METRIC_RANGE_MESSAGE));
    }

    // --- Property 3: perimeter explanation lists every wall length ---

    @Property(tries = 150)
    void perimeterExplanationListsEveryWallAndSumsToPerimeter(
            @ForAll("simplePolygons") List<RoomGeometry.Vertex> vertices) {
        RoomGeometry geometry = new RoomGeometry();
        geometry.setVertices(vertices);
        geometry.setWalls(fitWalls(List.of(), vertices.size()));
        BigDecimal perimeter = calculationService.calculate(geometry, BigDecimal.ONE).perimeter();

        RoomMetricExplainer.Input input = new RoomMetricExplainer.Input(
                geometry, BigDecimal.ONE, 0, null, null, 0, null, null, null, null,
                perimeter, BigDecimal.ZERO, BigDecimal.ZERO,
                MeasureSource.CALCULATED, MeasureSource.CALCULATED, MeasureSource.CALCULATED,
                MeasureSource.CALCULATED, Map.of());

        Map<String, MetricExplanation> out = RoomMetricExplainer.explain(input);

        assertThat(out).containsKey("perimeter");
        MetricExplanation explanation = out.get("perimeter");
        int n = vertices.size();
        assertThat(explanation.variables()).hasSize(n);
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            names.add("wall" + i);
        }
        assertThat(explanation.formula()).isEqualTo(String.join(" + ", names));
        assertThat(explanation.variables().keySet()).containsExactlyElementsOf(names);
        BigDecimal sum = explanation.variables().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum.subtract(perimeter).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.01").multiply(BigDecimal.valueOf(n)));
    }

    @Property(tries = 50)
    void perimeterExplanationAbsentWhenPerimeterIsManual(@ForAll("simplePolygons") List<RoomGeometry.Vertex> vertices) {
        RoomGeometry geometry = new RoomGeometry();
        geometry.setVertices(vertices);
        geometry.setWalls(fitWalls(List.of(), vertices.size()));

        RoomMetricExplainer.Input input = new RoomMetricExplainer.Input(
                geometry, BigDecimal.ONE, 0, null, null, 0, null, null, null, null,
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                MeasureSource.MANUAL, MeasureSource.MANUAL, MeasureSource.MANUAL,
                MeasureSource.MANUAL, Map.of());

        assertThat(RoomMetricExplainer.explain(input)).isNull();
    }

    // --- Assertions and helpers ---

    private record Opening(BigDecimal height, BigDecimal width) {
    }

    /** Independent re-implementation: the only opening of a type when the total count is exactly 1. */
    private Opening single(RoomGeometry geometry, OpeningType type) {
        int total = 0;
        RoomGeometry.Opening found = null;
        for (RoomGeometry.Wall wall : geometry.getWalls()) {
            if (wall == null || wall.getOpenings() == null) {
                continue;
            }
            for (RoomGeometry.Opening o : wall.getOpenings()) {
                if (o.getType() == type) {
                    total += o.getCount();
                    found = o;
                }
            }
        }
        if (total != 1) {
            return null;
        }
        return new Opening(BigDecimal.valueOf(found.getHeight()), BigDecimal.valueOf(found.getWidth()));
    }

    private static void apply(Consumer<BigDecimal> setValue, Consumer<MeasureSource> setSource, int mode,
                              BigDecimal manual) {
        switch (mode) {
            case 0 -> { setValue.accept(null); setSource.accept(null); }
            case 1 -> { setValue.accept(STALE); setSource.accept(MeasureSource.CALCULATED); }
            case 2 -> { setValue.accept(manual); setSource.accept(MeasureSource.MANUAL); }
            default -> { setValue.accept(null); setSource.accept(MeasureSource.MANUAL); }
        }
    }

    private static BigDecimal expectedValue(int mode, BigDecimal manual, BigDecimal derived) {
        return mode == 2 ? manual : derived;
    }

    /** Mode 2 keeps the manual value as MANUAL; every other mode is derived (maybe null) and CALCULATED. */
    private static void assertField(BigDecimal actual, MeasureSource source, int mode, BigDecimal manual,
                                    BigDecimal derived) {
        if (mode == 2) {
            assertThat(source).isEqualTo(MeasureSource.MANUAL);
            assertThat(actual).isEqualByComparingTo(manual);
        } else {
            assertThat(source).isEqualTo(MeasureSource.CALCULATED);
            if (derived == null) {
                assertThat(actual).isNull();
            } else {
                assertThat(actual).isNotNull();
                assertThat(actual.subtract(derived).abs()).isLessThanOrEqualTo(new BigDecimal("0.0051"));
            }
        }
    }

    private static void assertSame(BigDecimal a, BigDecimal b) {
        if (a == null) {
            assertThat(b).isNull();
        } else {
            assertThat(b).isEqualByComparingTo(a);
        }
    }

    /** RoomService.validateGeometry requires wall count == vertex count; trim or pad. */
    private static List<RoomGeometry.Wall> fitWalls(List<RoomGeometry.Wall> walls, int vertexCount) {
        List<RoomGeometry.Wall> fitted = new ArrayList<>(walls.subList(0, Math.min(walls.size(), vertexCount)));
        while (fitted.size() < vertexCount) {
            fitted.add(new RoomGeometry.Wall());
        }
        return fitted;
    }

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

    /** Simple polygons of 3..10 vertices (polar-angle ordering), filtered through the service's own validity check. */
    @Provide
    Arbitrary<List<RoomGeometry.Vertex>> simplePolygons() {
        Arbitrary<Double> coordinate = Arbitraries.doubles().between(-100.0d, 100.0d);
        Arbitrary<Point> points = Combinators.combine(coordinate, coordinate).as(Point::new);
        return points.list().uniqueElements(p -> p.x + "," + p.y).ofMinSize(3).ofMaxSize(10)
                .map(this::toSimplePolygon)
                .filter(PolygonChecks::isValidSimplePolygon);
    }

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

    /** 0..5 walls with optional gaps and 0..2 openings each, so total counts of 0, 1 and several all occur. */
    @Provide
    Arbitrary<List<RoomGeometry.Wall>> wallLists() {
        Arbitrary<Double> gap = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.doubles().between(0.0d, 10.0d).ofScale(2));
        Arbitrary<List<RoomGeometry.Opening>> openings = openings().list().ofMinSize(0).ofMaxSize(2);
        Arbitrary<RoomGeometry.Wall> wall = Combinators.combine(gap, gap, openings).as((wg, fg, ops) -> {
            RoomGeometry.Wall w = new RoomGeometry.Wall();
            w.setWallGap(wg);
            w.setFinishGap(fg);
            w.setOpenings(ops);
            return w;
        });
        return wall.list().ofMinSize(0).ofMaxSize(5);
    }

    private Arbitrary<RoomGeometry.Opening> openings() {
        Arbitrary<OpeningType> type = Arbitraries.of(OpeningType.DOOR, OpeningType.WINDOW);
        Arbitrary<Integer> count = Arbitraries.frequency(
                net.jqwik.api.Tuple.of(4, 1), net.jqwik.api.Tuple.of(1, 2));
        Arbitrary<Double> size = Arbitraries.doubles().between(0.1d, 3.0d).ofScale(2);
        return Combinators.combine(type, count, size, size).as((t, c, h, w) -> {
            RoomGeometry.Opening o = new RoomGeometry.Opening();
            o.setType(t);
            o.setCount(c);
            o.setHeight(h);
            o.setWidth(w);
            return o;
        });
    }

    @Provide
    Arbitrary<BigDecimal> ceilingHeight() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, BigDecimal.TEN).ofScale(2);
    }

    /** One mode (0..3) for each of the six fields. */
    @Provide
    Arbitrary<List<Integer>> modes() {
        return Arbitraries.integers().between(0, 3).list().ofSize(6);
    }

    /** In-range manual values (never equal to the stale value by construction of scale/range). */
    @Provide
    Arbitrary<List<BigDecimal>> manualValues() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("50")).ofScale(2)
                .list().ofSize(6);
    }

    @Provide
    Arbitrary<Integer> fieldIndex() {
        return Arbitraries.integers().between(0, 5);
    }

    @Provide
    Arbitrary<BigDecimal> badValue() {
        return Arbitraries.of(new BigDecimal("-0.01"), new BigDecimal("-5"),
                new BigDecimal("10000000000.00"), new BigDecimal("99999999999.99"));
    }

    private record Point(double x, double y) {
    }
}
