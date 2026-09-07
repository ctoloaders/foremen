package com.foremen.dao.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Structured JSON model describing a room's shape, bound to the {@code rooms.geometry jsonb}
 * column via {@code @JdbcTypeCode(SqlTypes.JSON)} and serialized/deserialized by Jackson.
 *
 * <p>A geometry is an ordered list of {@link Vertex vertices} and a parallel list of
 * {@link Wall walls}, where wall {@code i} connects vertex {@code i} to vertex
 * {@code (i + 1) mod n}. Each wall may carry a set of {@link Opening openings}
 * (doors/windows) plus per-wall {@code wallGap}/{@code finishGap} lengths.
 */
@Getter
@Setter
@NoArgsConstructor
public class RoomGeometry {

    /** Ordered polygon vertices; a valid polygon has at least 3. */
    private List<Vertex> vertices;

    /** Walls (edges) of the polygon; wall {@code i} connects vertex {@code i} to {@code (i+1) mod n}. */
    private List<Wall> walls;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Vertex {

        /** X coordinate of the vertex. */
        private double x;

        /** Y coordinate of the vertex. */
        private double y;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Wall {

        /** "Missing wall" length on this edge (mb); reduces the wall surface. Nullable. */
        private Double wallGap;

        /** "Missing finish" length on this edge (mb); reduces the finish surface. Nullable. */
        private Double finishGap;

        /** Openings (doors/windows) attached to this wall. */
        private List<Opening> openings;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Opening {

        /** Opening kind: {@code DOOR} or {@code WINDOW}. */
        private OpeningType type;

        /** Quantity of this opening (szt); must be positive. */
        private int count;

        /** Per-unit height (mb); must be positive. */
        private double height;

        /** Per-unit width (mb); must be positive. */
        private double width;
    }
}
