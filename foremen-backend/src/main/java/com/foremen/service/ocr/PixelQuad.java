package com.foremen.service.ocr;

import java.util.List;

/**
 * FOR-06-03 (Requirement 3.0, design §5.2) — a document quadrilateral in <strong>pixel</strong>
 * coordinates, produced by the pure {@link OcrHelpers#normalizedToPixelQuad(NormalizedQuad, int, int)}
 * by scaling a {@link NormalizedQuad} to a page's original width/height. The I/O orchestrator
 * (task 4.2) paints everything outside this quad white at the page's original dimensions.
 *
 * @param points the four corner points in pixel space (origin top-left), in the input order
 */
public record PixelQuad(List<Point> points) {

    /** A single pixel-space corner point. */
    public record Point(double x, double y) {
    }
}
