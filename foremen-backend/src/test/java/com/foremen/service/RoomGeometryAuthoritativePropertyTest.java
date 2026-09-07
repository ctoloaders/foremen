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
 * Property 5 (FOR-04-14): Geometry authoritative over manual.
 *
 * <p>When a room is normalized with a <em>present</em> geometry, the {@code RoomService}
 * pre-persist normalization ignores any directly supplied manual values for the five metrics
 * ({@code floorArea}, {@code perimeter}, {@code wallArea}, {@code doorArea}, {@code windowArea}) and
 * overwrites each with the {@link RoomCalculationService} result, stamping every one of the five
 * sources {@code CALCULATED} (Requirement 4.2). This property generates a valid geometry plus
 * arbitrary conflicting manual values (and conflicting {@code MANUAL} source flags), runs the
 * create-path hook, and asserts the resulting metrics equal the engine output with {@code CALCULATED}
 * sources regardless of what was supplied.
 *
 * <p>The normalization's reference resolution ({@code projectDao}/{@code roomTypeDao} lookups) is
 * satisfied with Mockito-mocked DAOs returning present entities so the property can focus on the
 * geometry-authoritative overwrite behavior; the calculation engine is the real
 * {@link RoomCalculationService}, so the expected values are computed by the same engine the service
 * uses.
 *
 * <p>Feature: FOR-04-14-room, Property 5
 *
 * <p><b>Validates: Requirements 4.2</b>
 */
@Tag("Feature: FOR-04-14-room, Property 5")
class RoomGeometryAuthoritativePropertyTest {

    private final RoomCalculationService calculationService = new RoomCalculationService();
    private final RoomService roomService = buildService(calculationService);

    // --- Property: geometry present -> five metrics overwritten with CALCULATED engine results ---

    @Property(tries = 100)
    void geometryOverwritesSuppliedManualValuesWithCalculatedResults(
            @ForAll("simplePolygons") List<RoomGeometry.Vertex> vertices,
            @ForAll("wallLists") List<RoomGeometry.Wall> walls,
            @ForAll("optionalCeilingHeight") BigDecimal ceilingHeight,
            @ForAll("manualMetrics") BigDecimal[] suppliedManual) {

        RoomGeometry geometry = new RoomGeometry();
        geometry.setVertices(vertices);
        geometry.setWalls(walls);

        RoomServiceExtendedModel model = new RoomServiceExtendedModel();
        model.setProjectId(1L);
        model.setRoomTypeId(2L);
        model.setGeometry(geometry);
        model.setCeilingHeight(ceilingHeight);

        // Supply arbitrary conflicting manual values and MANUAL source flags for all five metrics.
        model.setFloorArea(suppliedManual[0]);
        model.setFloorAreaSource(MeasureSource.MANUAL);
        model.setPerimeter(suppliedManual[1]);
        model.setPerimeterSource(MeasureSource.MANUAL);
        model.setWallArea(suppliedManual[2]);
        model.setWallAreaSource(MeasureSource.MANUAL);
        model.setDoorArea(suppliedManual[3]);
        model.setDoorAreaSource(MeasureSource.MANUAL);
        model.setWindowArea(suppliedManual[4]);
        model.setWindowAreaSource(MeasureSource.MANUAL);

        // Independent reference: the same engine the service delegates to.
        RoomMetrics expected = calculationService.calculate(geometry, ceilingHeight);

        roomService.validateCreate(model);

        // Every one of the five metrics equals the calculated result, sources all CALCULATED.
        assertThat(model.getFloorArea()).isEqualByComparingTo(expected.floorArea());
        assertThat(model.getFloorAreaSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(model.getPerimeter()).isEqualByComparingTo(expected.perimeter());
        assertThat(model.getPerimeterSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(model.getWallArea()).isEqualByComparingTo(expected.wallArea());
        assertThat(model.getWallAreaSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(model.getDoorArea()).isEqualByComparingTo(expected.doorArea());
        assertThat(model.getDoorAreaSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(model.getWindowArea()).isEqualByComparingTo(expected.windowArea());
        assertThat(model.getWindowAreaSource()).isEqualTo(MeasureSource.CALCULATED);
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
     * around their centroid, mirroring the P1 generator (a non-self-intersecting boundary).
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
     * Five in-range manual metric values ({@code [0, 9999999999.99]}) supplied by the caller; each is
     * expected to be ignored because geometry is authoritative.
     */
    @Provide
    Arbitrary<BigDecimal[]> manualMetrics() {
        Arbitrary<BigDecimal> metric =
                Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("9999999999.99")).ofScale(2);
        return metric.array(BigDecimal[].class).ofSize(5);
    }

    /** Simple immutable coordinate pair used only for generation. */
    private record Point(double x, double y) {
    }
}
