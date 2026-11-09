package com.foremen.service.dashboard;

import java.util.Collection;
import java.util.OptionalInt;

/**
 * Pure desktop-grid arithmetic (FOR-10 design "Layout service"): 4 columns x 9 rows = 36 cells,
 * {@code cell = row * 4 + col}. Footprints and occupancy are 36-bit masks held in a {@code long}.
 */
public final class GridMath {

    public static final int COLS = 4;
    public static final int ROWS = 9;
    public static final int CELLS = COLS * ROWS;

    private GridMath() {
    }

    public static int row(int cell) {
        return cell / COLS;
    }

    public static int col(int cell) {
        return cell % COLS;
    }

    /** True when the cell is in range, spans are positive and the whole footprint stays inside the grid. */
    public static boolean fits(int cell, int colSpan, int rowSpan) {
        if (cell < 0 || cell >= CELLS || colSpan < 1 || rowSpan < 1) {
            return false;
        }
        return col(cell) + colSpan <= COLS && row(cell) + rowSpan <= ROWS;
    }

    /** Bitmask of the cells covered by the footprint; {@code 0} when the footprint does not fit. */
    public static long footprint(int cell, int colSpan, int rowSpan) {
        if (!fits(cell, colSpan, rowSpan)) {
            return 0L;
        }
        long mask = 0L;
        for (int r = row(cell); r < row(cell) + rowSpan; r++) {
            for (int c = col(cell); c < col(cell) + colSpan; c++) {
                mask |= 1L << (r * COLS + c);
            }
        }
        return mask;
    }

    /** One placement's geometry. */
    public record Rect(int cell, int colSpan, int rowSpan) {
    }

    /** OR of the footprints of all placements. */
    public static long occupied(Collection<Rect> placements) {
        long mask = 0L;
        for (Rect p : placements) {
            mask |= footprint(p.cell(), p.colSpan(), p.rowSpan());
        }
        return mask;
    }

    /**
     * Scans {@code startAt..35}, then {@code 0..startAt-1}, returning the first cell whose footprint fits
     * the grid and does not intersect {@code occupied}.
     */
    public static OptionalInt firstFit(long occupied, int colSpan, int rowSpan, int startAt) {
        int start = (startAt < 0 || startAt >= CELLS) ? 0 : startAt;
        for (int i = 0; i < CELLS; i++) {
            int cell = (start + i) % CELLS;
            if (fits(cell, colSpan, rowSpan) && (footprint(cell, colSpan, rowSpan) & occupied) == 0L) {
                return OptionalInt.of(cell);
            }
        }
        return OptionalInt.empty();
    }
}
