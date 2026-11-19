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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * FOR-05-02c Property 2 (Preservation): inputs outside the bug condition keep their baseline
 * behavior of {@code RoomService} on UNFIXED code, and must keep it after the fix.
 *
 * <ul>
 *   <li>Rooms without geometry keep the submitted gaps, door/window sizes and metrics (Req 3.1).</li>
 *   <li>Geometry rooms with no manual root gap use the per-wall sums (Req 3.2) and the formula
 *       {@code wallArea = max(0, (perimeter - finishGap - wallGap) * ceilingHeight - doorArea -
 *       windowArea)}; counts, areas and perimeter equal the geometry-derived values and the five
 *       existing sources are {@code CALCULATED} (Req 3.4, 3.5).</li>
 *   <li>Per-wall gap formulas are written into the geometry (Req 3.3).</li>
 *   <li>Out-of-range gaps are rejected with the metric-range error (Req 3.8).</li>
 * </ul>
 *
 * <p>No assertion is made about door/window sizes of geometry rooms (the fix derives them) and no
 * gap/size {@code *Source} is sent as {@code MANUAL}.
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.8</b>
 */
@Tag("Feature: FOR-05-02c-bugfix-room-manual-dimensions, Property 2")
class RoomManualDimensionsPreservationPropertyTest {

    private static final BigDecimal MAX = new BigDecimal("9999999999.99");

    private final RoomCalculationService calculationService = new RoomCalculationService();
    private final RoomService roomService = buildService(calculationService);

    // --- Req 3.1: rooms without geometry keep the submitted values ---

    @Property(tries = 100)
    void noGeometryRoomKeepsSubmittedGapsSizesAndMetrics(
            @ForAll("metric") BigDecimal wallGap,
            @ForAll("metric") BigDecimal finishGap,
            @ForAll("metric") BigDecimal doorHeight,
            @ForAll("metric") BigDecimal doorWidth,
            @ForAll("metric") BigDecimal windowHeight,
            @ForAll("metric") BigDecimal windowWidth,
            @ForAll("metric") BigDecimal perimeter,
            @ForAll("metric") BigDecimal wallArea,
            @ForAll("metric") BigDecimal doorArea,
            @ForAll("metric") BigDecimal windowArea,
            @ForAll("optionalCeilingHeight") BigDecimal ceilingHeight) {

        RoomServiceExtendedModel model = baseModel();
        model.setCeilingHeight(ceilingHeight);
        model.setWallGap(wallGap);
        model.setFinishGap(finishGap);
        model.setDoorHeight(doorHeight);
        model.setDoorWidth(doorWidth);
        model.setWindowHeight(windowHeight);
        model.setWindowWidth(windowWidth);
        model.setPerimeter(perimeter);
        model.setWallArea(wallArea);
        model.setDoorArea(doorArea);
        model.setWindowArea(windowArea);

        roomService.validateUpdate(null, model);

        assertThat(model.getWallGap()).isEqualByComparingTo(wallGap);
        assertThat(model.getFinishGap()).isEqualByComparingTo(finishGap);
        assertThat(model.getDoorHeight()).isEqualByComparingTo(doorHeight);
        assertThat(model.getDoorWidth()).isEqualByComparingTo(doorWidth);
        assertThat(model.getWindowHeight()).isEqualByComparingTo(windowHeight);
        assertThat(model.getWindowWidth()).isEqualByComparingTo(windowWidth);
        assertThat(model.getPerimeter()).isEqualByComparingTo(perimeter);
        assertThat(model.getWallArea()).isEqualByComparingTo(wallArea);
        assertThat(model.getDoorArea()).isEqualByComparingTo(doorArea);
        assertThat(model.getWindowArea()).isEqualByComparingTo(windowArea);
        // Supplied metrics are stamped MANUAL on the geometry-less path; absent ones stay null.
        assertThat(model.getPerimeterSource()).isEqualTo(MeasureSource.MANUAL);
        assertThat(model.getWallAreaSource()).isEqualTo(MeasureSource.MANUAL);
        assertThat(model.getDoorAreaSource()).isEqualTo(MeasureSource.MANUAL);
        assertThat(model.getWindowAreaSource()).isEqualTo(MeasureSource.MANUAL);
        assertThat(model.getFloorAreaSource()).isNull();
    }

    @Property(tries = 50)
    void noGeometryRoomWithAbsentValuesStaysNull(@ForAll("optionalCeilingHeight") BigDecimal ceilingHeight) {
        RoomServiceExtendedModel model = baseModel();
        model.setCeilingHeight(ceilingHeight);

        roomService.validateUpdate(null, model);

        assertThat(model.getWallGap()).isNull();
        assertThat(model.getFinishGap()).isNull();
        assertThat(model.getDoorHeight()).isNull();
        assertThat(model.getWallArea()).isNull();
        assertThat(model.getWallAreaSource()).isNull();
        assertThat(model.getPerimeterSource()).isNull();
    }

    // --- Req 3.2, 3.4, 3.5: geometry room without manual root gap/size ---

    @Property(tries = 150)
    void geometryRoomWithoutManualGapsUsesPerWallSumsAndGeometryMetrics(
            @ForAll("geometries") RoomGeometry geometry,
            @ForAll("optionalCeilingHeight") BigDecimal ceilingHeight,
            @ForAll("staleGap") BigDecimal submittedWallGap,
            @ForAll("staleGap") BigDecimal submittedFinishGap) {

        RoomServiceExtendedModel model = baseModel();
        model.setGeometry(geometry);
        model.setCeilingHeight(ceilingHeight);
        // A root gap without a MANUAL source is not a manual override: geometry sums win.
        model.setWallGap(submittedWallGap);
        model.setFinishGap(submittedFinishGap);

        RoomMetrics expected = calculationService.calculate(geometry, ceilingHeight);

        roomService.validateUpdate(null, model);

        BigDecimal expectedWallGap = sumGap(geometry, true);
        BigDecimal expectedFinishGap = sumGap(geometry, false);
        assertThat(model.getWallGap()).isEqualByComparingTo(expected.wallGap());
        assertThat(model.getFinishGap()).isEqualByComparingTo(expected.finishGap());
        assertThat(model.getWallGap().subtract(expectedWallGap).abs()).isLessThanOrEqualTo(new BigDecimal("0.011"));
        assertThat(model.getFinishGap().subtract(expectedFinishGap).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.011"));

        // Independent formula over the persisted components.
        BigDecimal height = ceilingHeight == null ? BigDecimal.ZERO : ceilingHeight;
        BigDecimal wallArea = model.getPerimeter().subtract(model.getFinishGap()).subtract(model.getWallGap())
                .multiply(height).subtract(model.getDoorArea()).subtract(model.getWindowArea())
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        assertThat(model.getWallArea()).isEqualByComparingTo(wallArea);
        assertThat(model.getWallArea()).isEqualByComparingTo(expected.wallArea());

        assertThat(model.getDoorCount()).isEqualTo(expected.doorCount());
        assertThat(model.getWindowCount()).isEqualTo(expected.windowCount());
        assertThat(model.getDoorArea()).isEqualByComparingTo(expected.doorArea());
        assertThat(model.getWindowArea()).isEqualByComparingTo(expected.windowArea());
        assertThat(model.getFloorArea()).isEqualByComparingTo(expected.floorArea());
        assertThat(model.getPerimeter()).isEqualByComparingTo(expected.perimeter());

        assertThat(model.getFloorAreaSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(model.getWallAreaSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(model.getPerimeterSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(model.getDoorAreaSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(model.getWindowAreaSource()).isEqualTo(MeasureSource.CALCULATED);
    }

    @Property(tries = 50)
    void zeroGapsAndLargeOpeningsClampWallAreaAtZero(@ForAll("geometries") RoomGeometry geometry) {
        RoomServiceExtendedModel model = baseModel();
        model.setGeometry(geometry);
        model.setCeilingHeight(BigDecimal.ZERO); // wall area must clamp to 0 (never negative)

        roomService.validateUpdate(null, model);

        assertThat(model.getWallArea()).isEqualByComparingTo("0");
    }

    // --- Req 3.4: five-metric MANUAL rule is untouched; gaps still summed ---

    @Test
    void manualWallAreaIsKeptWhileGapsStillComeFromGeometry() {
        RoomGeometry geometry = rectangle(4.0, 3.0, 0.5, 0.25, List.of());
        RoomServiceExtendedModel model = baseModel();
        model.setGeometry(geometry);
        model.setCeilingHeight(new BigDecimal("2.5"));
        model.setWallArea(new BigDecimal("33.33"));
        model.setWallAreaSource(MeasureSource.MANUAL);
        model.setPerimeter(new BigDecimal("20.00"));
        model.setPerimeterSource(MeasureSource.MANUAL);

        roomService.validateUpdate(null, model);

        assertThat(model.getWallArea()).isEqualByComparingTo("33.33");
        assertThat(model.getWallAreaSource()).isEqualTo(MeasureSource.MANUAL);
        assertThat(model.getPerimeter()).isEqualByComparingTo("20.00");
        assertThat(model.getPerimeterSource()).isEqualTo(MeasureSource.MANUAL);
        assertThat(model.getFloorAreaSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(model.getWallGap()).isEqualByComparingTo("0.50");
        assertThat(model.getFinishGap()).isEqualByComparingTo("0.25");
    }

    // --- Req 3.3: per-wall gap formulas are evaluated and written into the geometry ---

    @Test
    void perWallGapFormulaIsWrittenIntoGeometryAndSummed() {
        RoomGeometry geometry = rectangle(4.0, 3.0, null, null, List.of());
        RoomServiceExtendedModel model = baseModel();
        model.setGeometry(geometry);
        model.setCeilingHeight(new BigDecimal("2.7"));
        Map<String, String> formulas = new LinkedHashMap<>();
        formulas.put("geometry.walls[0].wallGap", "1+0.5");
        formulas.put("geometry.walls[1].finishGap", "2*0.75");
        model.setDimensionFormulas(formulas);

        roomService.validateUpdate(null, model);

        assertThat(geometry.getWalls().get(0).getWallGap()).isEqualTo(1.5);
        assertThat(geometry.getWalls().get(1).getFinishGap()).isEqualTo(1.5);
        assertThat(model.getWallGap()).isEqualByComparingTo("1.50");
        assertThat(model.getFinishGap()).isEqualByComparingTo("1.50");
        assertThat(model.getDimensionFormulas()).containsKeys("geometry.walls[0].wallGap",
                "geometry.walls[1].finishGap");
    }

    // --- Req 3.8: out-of-range values are rejected with the metric-range error ---

    @Test
    void outOfRangeGapsAreRejectedOnNoGeometryPath() {
        for (BigDecimal bad : List.of(new BigDecimal("-0.01"), new BigDecimal("9999999999.99").add(new BigDecimal("0.01")))) {
            RoomServiceExtendedModel wall = baseModel();
            wall.setWallGap(bad);
            assertThatThrownBy(() -> roomService.validateUpdate(null, wall))
                    .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                        assertThat(e.getMessage()).isEqualTo(RoomService.METRIC_RANGE_MESSAGE);
                        assertThat(e.getStatus().value()).isEqualTo(400);
                    });

            RoomServiceExtendedModel finish = baseModel();
            finish.setFinishGap(bad);
            assertThatThrownBy(() -> roomService.validateUpdate(null, finish))
                    .isInstanceOfSatisfying(ForemenApiException.class,
                            e -> assertThat(e.getMessage()).isEqualTo(RoomService.METRIC_RANGE_MESSAGE));
        }
    }

    @Test
    void outOfRangeManualFiveMetricIsRejectedOnGeometryPath() {
        RoomServiceExtendedModel model = baseModel();
        model.setGeometry(rectangle(4.0, 3.0, null, null, List.of()));
        model.setWallArea(new BigDecimal("-1"));
        model.setWallAreaSource(MeasureSource.MANUAL);

        assertThatThrownBy(() -> roomService.validateUpdate(null, model))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(RoomService.METRIC_RANGE_MESSAGE));
    }

    // --- Helpers ---

    private RoomServiceExtendedModel baseModel() {
        RoomServiceExtendedModel model = new RoomServiceExtendedModel();
        model.setProjectId(1L);
        model.setRoomTypeId(2L);
        return model;
    }

    /** Rounded Σ of per-wall gaps (independent of the engine's summation order, within 0.01). */
    private BigDecimal sumGap(RoomGeometry geometry, boolean wallGap) {
        double sum = 0.0d;
        for (RoomGeometry.Wall wall : geometry.getWalls()) {
            Double gap = wallGap ? wall.getWallGap() : wall.getFinishGap();
            if (gap != null) {
                sum += gap;
            }
        }
        return BigDecimal.valueOf(sum).setScale(2, RoundingMode.HALF_UP);
    }

    /** Axis-aligned rectangle; per-wall gaps on wall 0 (wallGap) and wall 1 (finishGap). */
    private RoomGeometry rectangle(double w, double h, Double wallGap, Double finishGap,
                                   List<RoomGeometry.Opening> openingsOnWall0) {
        double[][] pts = {{0, 0}, {w, 0}, {w, h}, {0, h}};
        List<RoomGeometry.Vertex> vertices = new ArrayList<>();
        for (double[] p : pts) {
            RoomGeometry.Vertex v = new RoomGeometry.Vertex();
            v.setX(p[0]);
            v.setY(p[1]);
            vertices.add(v);
        }
        List<RoomGeometry.Wall> walls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RoomGeometry.Wall wall = new RoomGeometry.Wall();
            wall.setOpenings(i == 0 ? new ArrayList<>(openingsOnWall0) : new ArrayList<>());
            if (i == 0) {
                wall.setWallGap(wallGap);
            }
            if (i == 1) {
                wall.setFinishGap(finishGap);
            }
            walls.add(wall);
        }
        RoomGeometry geometry = new RoomGeometry();
        geometry.setVertices(vertices);
        geometry.setWalls(walls);
        return geometry;
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

    @Provide
    Arbitrary<BigDecimal> metric() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, MAX).ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> staleGap() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("50")).ofScale(2));
    }

    @Provide
    Arbitrary<BigDecimal> optionalCeilingHeight() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.bigDecimals().between(BigDecimal.ZERO, BigDecimal.TEN).ofScale(2));
    }

    /** Convex n-gon (n = 3..8) with one wall per vertex, random per-wall gaps and 0..3 openings per wall. */
    @Provide
    Arbitrary<RoomGeometry> geometries() {
        Arbitrary<Integer> n = Arbitraries.integers().between(3, 8);
        Arbitrary<Double> radius = Arbitraries.doubles().between(1.0d, 20.0d);
        return Combinators.combine(n, radius).as((count, r) -> new Object[] {count, r})
                .flatMap(a -> {
                    int count = (Integer) a[0];
                    double r = (Double) a[1];
                    return walls().list().ofSize(count).map(walls -> {
                        List<RoomGeometry.Vertex> vertices = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            double angle = 2 * Math.PI * i / count;
                            RoomGeometry.Vertex v = new RoomGeometry.Vertex();
                            v.setX(r * Math.cos(angle));
                            v.setY(r * Math.sin(angle));
                            vertices.add(v);
                        }
                        RoomGeometry geometry = new RoomGeometry();
                        geometry.setVertices(vertices);
                        geometry.setWalls(walls);
                        return geometry;
                    });
                });
    }

    private Arbitrary<RoomGeometry.Wall> walls() {
        Arbitrary<Double> gap = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.just(0.0d),
                Arbitraries.doubles().between(0.0d, 5.0d).ofScale(2));
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
        Arbitrary<Integer> count = Arbitraries.integers().between(1, 4);
        Arbitrary<Double> height = Arbitraries.doubles().between(0.1d, 3.0d).ofScale(2);
        Arbitrary<Double> width = Arbitraries.doubles().between(0.1d, 3.0d).ofScale(2);
        return Combinators.combine(type, count, height, width).as((t, c, h, w) -> {
            RoomGeometry.Opening opening = new RoomGeometry.Opening();
            opening.setType(t);
            opening.setCount(c);
            opening.setHeight(h);
            opening.setWidth(w);
            return opening;
        });
    }
}
