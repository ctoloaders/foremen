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

    /** Result of testing a footprint against the grid and the occupied mask. */
    public enum Placement { OK, INVALID, OCCUPIED }

    /** A free rectangle (columns x rows) starting at a cell. */
    public record Fit(int cols, int rows) {
        public int area() {
            return cols * rows;
        }
    }

    /** True when the cell is in range and its bit is not set. */
    public static boolean isFree(long occupied, int cell) {
        return cell >= 0 && cell < CELLS && (occupied & (1L << cell)) == 0L;
    }

    /** Ascending list of free cells. */
    public static java.util.List<Integer> freeCells(long occupied) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int c = 0; c < CELLS; c++) {
            if (isFree(occupied, c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** INVALID when the footprint is outside the grid, OCCUPIED when it overlaps, else OK. */
    public static Placement classify(long occupied, int cell, int colSpan, int rowSpan) {
        if (!fits(cell, colSpan, rowSpan)) {
            return Placement.INVALID;
        }
        return (footprint(cell, colSpan, rowSpan) & occupied) != 0L ? Placement.OCCUPIED : Placement.OK;
    }

    /** Maximal free rectangles starting at {@code cell}, ordered by width; empty when the cell is not free. */
    public static java.util.List<Fit> maximalFits(long occupied, int cell) {
        if (!isFree(occupied, cell)) {
            return java.util.List.of();
        }
        int maxW = 0;
        while (col(cell) + maxW < COLS && isFree(occupied, cell + maxW)) {
            maxW++;
        }
        int[] h = new int[maxW + 2];
        for (int w = 1; w <= maxW; w++) {
            int height = 0;
            while (row(cell) + height < ROWS
                    && classify(occupied, cell, w, height + 1) == Placement.OK) {
                height++;
            }
            h[w] = height;
        }
        java.util.List<Fit> out = new java.util.ArrayList<>();
        for (int w = 1; w <= maxW; w++) {
            if (h[w] > h[w + 1]) {
                out.add(new Fit(w, h[w]));
            }
        }
        return out;
    }

    /** Largest-area maximal fit; ties go to the wider one. */
    public static java.util.Optional<Fit> fitSize(long occupied, int cell) {
        Fit best = null;
        for (Fit f : maximalFits(occupied, cell)) {
            if (best == null || f.area() > best.area() || (f.area() == best.area() && f.cols() > best.cols())) {
                best = f;
            }
        }
        return java.util.Optional.ofNullable(best);
    }

    /** True when the size is contained in at least one maximal fit. */
    public static boolean fitsInAny(java.util.List<Fit> maximal, int colSpan, int rowSpan) {
        if (colSpan < 1 || rowSpan < 1) {
            return false;
        }
        for (Fit f : maximal) {
            if (colSpan <= f.cols() && rowSpan <= f.rows()) {
                return true;
            }
        }
        return false;
    }

    /** Highest row covered by any footprint, or -1 when none. */
    public static int lastOccupiedRow(Collection<Rect> placements) {
        int last = -1;
        for (Rect p : placements) {
            if (fits(p.cell(), p.colSpan(), p.rowSpan())) {
                last = Math.max(last, row(p.cell()) + p.rowSpan() - 1);
            }
        }
        return last;
    }

    /** Rows shown with Add_Slots: min(9, lastOccupiedRow + 2); 1 for an empty grid. */
    public static int visibleRows(Collection<Rect> placements) {
        return Math.min(ROWS, lastOccupiedRow(placements) + 2);
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
