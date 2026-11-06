package com.foremen.service.roomsimport.geometry;

import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.RoomGeometry;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties of {@link GeometryNormalizer}: idempotence, area scaling, wall alignment.
 *
 * <p>Feature: FOR-05-02-rooms-autoimport, Properties 4 and 5
 *
 * <p><b>Validates: Requirements 5.4</b>
 */
@Tag("Feature: FOR-05-02-rooms-autoimport, Properties 4-5")
class GeometryNormalizerPropertyTest {

    private final GeometryNormalizer normalizer = new GeometryNormalizer();

    @Property(tries = 200)
    void normalizationIsIdempotent(@ForAll("geometries") RoomGeometry g, @ForAll("units") String unit) {
        var first = normalizer.normalize(g, unit, "r1").geometry();
        Assume.that(first != null);

        var second = normalizer.normalize(first, "m", "r1").geometry();

        assertThat(second).usingRecursiveComparison().isEqualTo(first);
    }

    @Property(tries = 200)
    void centimetreConversionScalesAreaByOneTenThousandth(@ForAll("geometries") RoomGeometry g) {
        var result = normalizer.normalize(g, "cm", "r1").geometry();
        Assume.that(result != null);

        double rawArea = Math.abs(GeometryNormalizer.signedArea(g.getVertices()));
        double area = GeometryNormalizer.signedArea(result.getVertices());

        assertThat(area).isGreaterThanOrEqualTo(-1e-9);
        assertThat(area).isCloseTo(rawArea * 1e-4, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Property(tries = 200)
    void wallsAlignWithVertices(@ForAll("geometries") RoomGeometry g, @ForAll("units") String unit) {
        var result = normalizer.normalize(g, unit, "r1").geometry();
        Assume.that(result != null);

        assertThat(result.getWalls()).hasSameSizeAs(result.getVertices());
        assertThat(result.getVertices().size()).isGreaterThanOrEqualTo(3);
    }

    @Provide
    Arbitrary<String> units() {
        return Arbitraries.of("m", "cm", "mm", "ft", null);
    }

    @Provide
    Arbitrary<RoomGeometry> geometries() {
        Arbitrary<RoomGeometry.Vertex> vertex = Arbitraries.integers().between(0, 6).tuple2()
                .map(t -> GeometryNormalizerTest.v(t.get1() * 100.0, t.get2() * 100.0));
        Arbitrary<RoomGeometry.Opening> opening = Arbitraries.integers().between(-1, 3).flatMap(count ->
                Arbitraries.doubles().between(-0.5, 2.5).flatMap(h ->
                        Arbitraries.doubles().between(-0.5, 2.0).map(w ->
                                GeometryNormalizerTest.opening(
                                        count % 2 == 0 ? OpeningType.DOOR : OpeningType.WINDOW, count, h, w,
                                        count == 2 ? 0.3 : null))));
        Arbitrary<RoomGeometry.Wall> wall = opening.list().ofMaxSize(3).map(os -> {
            RoomGeometry.Wall w = new RoomGeometry.Wall();
            w.setOpenings(new ArrayList<>(os));
            return w;
        });
        return vertex.list().ofMinSize(3).ofMaxSize(8).flatMap(vs ->
                wall.list().ofMaxSize(11).map(ws -> GeometryNormalizerTest.geometry(new ArrayList<>(vs),
                        new ArrayList<>(ws))));
    }
}
