package com.foremen.service.dashboard;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Grid-safety properties of {@link GridMath}.
 * <b>Validates: Requirements 1.2, 3.1, 3.3</b>
 */
class GridMathPropertyTest {

    @Test
    void examples() {
        assertThat(GridMath.fits(3, 2, 1)).isFalse();
        assertThat(GridMath.fits(2, 2, 1)).isTrue();
        assertThat(GridMath.fits(32, 1, 2)).isFalse();
        assertThat(GridMath.fits(35, 1, 1)).isTrue();
        assertThat(Long.bitCount(GridMath.footprint(5, 2, 3))).isEqualTo(6);
        assertThat(GridMath.firstFit(0L, 1, 1, 0)).hasValue(0);
        assertThat(GridMath.firstFit(0b1L, 1, 1, 0)).hasValue(1);
        assertThat(GridMath.firstFit(-1L >>> 28, 1, 1, 0)).isEmpty();
    }

    @Property
    void footprintHasExactlyAreaBitsInsideGrid(@ForAll @IntRange(min = 0, max = 35) int cell,
                                               @ForAll @IntRange(min = 1, max = 4) int cs,
                                               @ForAll @IntRange(min = 1, max = 9) int rs) {
        long fp = GridMath.footprint(cell, cs, rs);
        if (GridMath.fits(cell, cs, rs)) {
            assertThat(Long.bitCount(fp)).isEqualTo(cs * rs);
            assertThat(fp & (1L << cell)).isNotZero();
            assertThat(fp >>> GridMath.CELLS).isZero();
        } else {
            assertThat(fp).isZero();
        }
    }

    @Property
    void firstFitResultFitsAndDoesNotOverlap(@ForAll("occupancy") long occupied,
                                             @ForAll @IntRange(min = 1, max = 4) int cs,
                                             @ForAll @IntRange(min = 1, max = 9) int rs,
                                             @ForAll @IntRange(min = 0, max = 35) int start) {
        OptionalInt r = GridMath.firstFit(occupied, cs, rs, start);
        if (r.isPresent()) {
            assertThat(GridMath.fits(r.getAsInt(), cs, rs)).isTrue();
            assertThat(GridMath.footprint(r.getAsInt(), cs, rs) & occupied).isZero();
        } else {
            for (int c = 0; c < GridMath.CELLS; c++) {
                assertThat(GridMath.fits(c, cs, rs) && (GridMath.footprint(c, cs, rs) & occupied) == 0L).isFalse();
            }
        }
    }

    /** Repeatedly placing via firstFit never produces overlapping or out-of-grid placements. */
    @Property
    void sequentialFirstFitNeverOverlaps(@ForAll("sizes") List<int[]> sizes) {
        long occupied = 0L;
        List<GridMath.Rect> placed = new ArrayList<>();
        for (int[] s : sizes) {
            OptionalInt cell = GridMath.firstFit(occupied, s[0], s[1], 0);
            if (cell.isPresent()) {
                long fp = GridMath.footprint(cell.getAsInt(), s[0], s[1]);
                assertThat(fp & occupied).isZero();
                occupied |= fp;
                placed.add(new GridMath.Rect(cell.getAsInt(), s[0], s[1]));
            }
        }
        int area = placed.stream().mapToInt(p -> p.colSpan() * p.rowSpan()).sum();
        assertThat(Long.bitCount(GridMath.occupied(placed))).isEqualTo(area);
        assertThat(area).isLessThanOrEqualTo(GridMath.CELLS);
    }

    @Test
    void a1Examples() {
        // 1x3 gap at cell 0 with a 2x1 reachable: col 1 blocked from row 1 down
        long occ = GridMath.footprint(1, 1, 8) & ~GridMath.footprint(1, 1, 1);
        assertThat(GridMath.maximalFits(occ, 0))
                .containsExactly(new GridMath.Fit(1, 9), new GridMath.Fit(4, 1));
        assertThat(GridMath.fitSize(occ, 0)).hasValue(new GridMath.Fit(1, 9));
        assertThat(GridMath.fitsInAny(GridMath.maximalFits(occ, 0), 2, 1)).isTrue();
        assertThat(GridMath.classify(0L, 36, 1, 1)).isEqualTo(GridMath.Placement.INVALID);
        assertThat(GridMath.classify(1L, 0, 1, 1)).isEqualTo(GridMath.Placement.OCCUPIED);
        assertThat(GridMath.visibleRows(List.of())).isEqualTo(1);
        assertThat(GridMath.lastOccupiedRow(List.of())).isEqualTo(-1);
        assertThat(GridMath.visibleRows(List.of(new GridMath.Rect(32, 1, 1)))).isEqualTo(9);
        assertThat(GridMath.fitSize(0b1L, 0)).isEmpty();
    }

    @Property
    void classifyMatchesFitsAndOverlapAndFitsInAnyAgrees(@ForAll("occupancy") long occupied,
                                                         @ForAll @IntRange(min = 0, max = 35) int cell,
                                                         @ForAll @IntRange(min = 1, max = 4) int cs,
                                                         @ForAll @IntRange(min = 1, max = 9) int rs) {
        GridMath.Placement p = GridMath.classify(occupied, cell, cs, rs);
        boolean ok = GridMath.fits(cell, cs, rs) && (GridMath.footprint(cell, cs, rs) & occupied) == 0L;
        assertThat(p == GridMath.Placement.OK).isEqualTo(ok);
        assertThat(GridMath.fitsInAny(GridMath.maximalFits(occupied, cell), cs, rs)).isEqualTo(ok);
        assertThat(GridMath.freeCells(occupied)).hasSize(GridMath.CELLS - Long.bitCount(occupied));
    }

    // ---- Brute-force reference (independent of GridMath's bitmask helpers) ----

    private static boolean bit(long mask, int r, int c) {
        return (mask >>> (r * 4 + c) & 1L) == 1L;
    }

    /** True when every cell of the w x h rectangle at (r, c) is inside the 4x9 grid and free. */
    private static boolean refRectFree(long occ, int r, int c, int w, int h) {
        if (w < 1 || h < 1 || r < 0 || c < 0 || r + h > 9 || c + w > 4) {
            return false;
        }
        for (int rr = r; rr < r + h; rr++) {
            for (int cc = c; cc < c + w; cc++) {
                if (bit(occ, rr, cc)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Brute-force maximal rectangles anchored at the cell: free and not extendable by a column or a row. */
    private static List<GridMath.Fit> refMaximal(long occ, int cell) {
        List<GridMath.Fit> out = new ArrayList<>();
        int r = cell / 4;
        int c = cell % 4;
        for (int w = 1; w <= 4; w++) {
            for (int h = 1; h <= 9; h++) {
                if (refRectFree(occ, r, c, w, h)
                        && !refRectFree(occ, r, c, w + 1, h)
                        && !refRectFree(occ, r, c, w, h + 1)) {
                    out.add(new GridMath.Fit(w, h));
                }
            }
        }
        out.sort(java.util.Comparator.comparingInt(GridMath.Fit::cols));
        return out;
    }

    /**
     * Property 15 (backend side): classify is INVALID iff outside the grid, OCCUPIED iff it fits and
     * intersects, OK otherwise, judged against a cell-by-cell reference over a wide input range.
     * <b>Validates: Requirements 19.3, 21.8, 21.11</b>
     */
    @Property(tries = 500)
    void property15ClassifyMatchesBruteForce(@ForAll("occupancy") long occupied,
                                             @ForAll @IntRange(min = -5, max = 40) int cell,
                                             @ForAll @IntRange(min = 0, max = 5) int cs,
                                             @ForAll @IntRange(min = 0, max = 10) int rs) {
        boolean inside = cell >= 0 && cell < 36 && cs >= 1 && rs >= 1
                && cell % 4 + cs <= 4 && cell / 4 + rs <= 9;
        GridMath.Placement expected;
        if (!inside) {
            expected = GridMath.Placement.INVALID;
        } else {
            expected = refRectFree(occupied, cell / 4, cell % 4, cs, rs)
                    ? GridMath.Placement.OK : GridMath.Placement.OCCUPIED;
        }
        assertThat(GridMath.classify(occupied, cell, cs, rs)).isEqualTo(expected);
    }

    /**
     * Property 20: freeCells is the ascending complement of the occupied union and is disjoint from
     * every footprint; visibleRows is 1 for an empty layout, else min(9, lastOccupiedRow + 2), and
     * Add_Slots (free cells in visible rows) are free cells in visible rows.
     * <b>Validates: Requirements 20.1, 20.2, 20.5, 20.10</b>
     */
    @Property(tries = 300)
    void property20FreeCellsAndVisibleRows(@ForAll("rects") List<GridMath.Rect> rects) {
        long occupied = GridMath.occupied(rects);
        List<Integer> free = GridMath.freeCells(occupied);

        List<Integer> expectedFree = new ArrayList<>();
        for (int cell = 0; cell < 36; cell++) {
            boolean covered = false;
            for (GridMath.Rect p : rects) {
                if (refRectFree(0L, p.cell() / 4, p.cell() % 4, p.colSpan(), p.rowSpan())
                        && cell / 4 >= p.cell() / 4 && cell / 4 < p.cell() / 4 + p.rowSpan()
                        && cell % 4 >= p.cell() % 4 && cell % 4 < p.cell() % 4 + p.colSpan()) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                expectedFree.add(cell);
            }
        }
        assertThat(free).isEqualTo(expectedFree).isSorted();
        for (GridMath.Rect p : rects) {
            assertThat(GridMath.footprint(p.cell(), p.colSpan(), p.rowSpan()) & GridMath.occupied(List.of()))
                    .isZero();
            for (int cell : free) {
                assertThat(GridMath.footprint(p.cell(), p.colSpan(), p.rowSpan()) & (1L << cell)).isZero();
            }
        }

        int last = -1;
        for (GridMath.Rect p : rects) {
            if (refRectFree(0L, p.cell() / 4, p.cell() % 4, p.colSpan(), p.rowSpan())) {
                last = Math.max(last, p.cell() / 4 + p.rowSpan() - 1);
            }
        }
        assertThat(GridMath.lastOccupiedRow(rects)).isEqualTo(last);
        int visible = GridMath.visibleRows(rects);
        assertThat(visible).isEqualTo(rects.isEmpty() || last < 0 ? 1 : Math.min(9, last + 2));
        assertThat(visible).isBetween(1, 9);

        for (int cell : free) {
            if (cell / 4 < visible) {
                assertThat(GridMath.classify(occupied, cell, 1, 1)).isEqualTo(GridMath.Placement.OK);
            }
        }
        // every cell below the visible rows' last occupied row + 1 is free when rows remain hidden
        for (int cell = visible * 4; cell < 36; cell++) {
            assertThat(GridMath.isFree(occupied, cell)).isTrue();
        }
    }

    /**
     * Property 21: maximalFits equals the brute-force set of maximal free rectangles; a size fits iff
     * contained in some maximal fit (equivalently classify == OK); fitSize is the largest area, ties
     * to the wider one.
     * <b>Validates: Requirements 21.1, 21.2</b>
     */
    @Property(tries = 500)
    void property21FitSizeAndMaximalFitsAreCorrect(@ForAll("occupancy") long occupied,
                                                   @ForAll @IntRange(min = 0, max = 35) int cell) {
        List<GridMath.Fit> expected = refMaximal(occupied, cell);
        List<GridMath.Fit> actual = GridMath.maximalFits(occupied, cell);
        assertThat(actual).containsExactlyElementsOf(expected);

        for (int cs = 1; cs <= 4; cs++) {
            for (int rs = 1; rs <= 9; rs++) {
                boolean ok = refRectFree(occupied, cell / 4, cell % 4, cs, rs);
                assertThat(GridMath.fitsInAny(actual, cs, rs)).isEqualTo(ok);
                assertThat(GridMath.classify(occupied, cell, cs, rs) == GridMath.Placement.OK).isEqualTo(ok);
            }
        }

        java.util.Optional<GridMath.Fit> best = GridMath.fitSize(occupied, cell);
        if (expected.isEmpty()) {
            assertThat(best).isEmpty();
        } else {
            GridMath.Fit ref = expected.get(0);
            for (GridMath.Fit f : expected) {
                if (f.area() > ref.area() || (f.area() == ref.area() && f.cols() > ref.cols())) {
                    ref = f;
                }
            }
            assertThat(best).hasValue(ref);
        }
    }

    @Provide
    Arbitrary<List<GridMath.Rect>> rects() {
        return Combinators.combine(Arbitraries.integers().between(0, 35),
                        Arbitraries.integers().between(1, 4), Arbitraries.integers().between(1, 9))
                .as(GridMath.Rect::new).list().ofMaxSize(8);
    }

    @Provide
    Arbitrary<Long> occupancy() {
        return Arbitraries.longs().between(0L, (1L << GridMath.CELLS) - 1);
    }

    @Provide
    Arbitrary<List<int[]>> sizes() {
        return Combinators.combine(Arbitraries.integers().between(1, 4), Arbitraries.integers().between(1, 9))
                .as((c, r) -> new int[]{c, r}).list().ofMaxSize(40);
    }
}
