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
