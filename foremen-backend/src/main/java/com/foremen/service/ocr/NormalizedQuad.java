package com.foremen.service.ocr;

import java.util.List;

/**
 * FOR-06-03 (Requirement 3.0, design §5.2) — a document quadrilateral in <strong>normalized</strong>
 * {@code [0,1]} coordinates (origin top-left), as returned by the Google vision corner-detect model
 * and parsed by the pure {@link OcrHelpers#parseCornerResponse(String)}. The four points are the
 * detected corners in model order; every coordinate is clamped into {@code [0,1]}.
 *
 * @param points the four corner points; always exactly four, each clamped to {@code [0,1]}
 */
public record NormalizedQuad(List<Point> points) {

    /** A single normalized corner point; {@code x} and {@code y} are each in {@code [0,1]}. */
    public record Point(double x, double y) {
    }
}
