package com.foremen.service.ocr;

/**
 * FOR-06-03 (Requirement 3.0, design §5.2) — one white-background rendition page produced by the OCR
 * cleanup step and stored via {@code ImageStorage} by the orchestrator (task 4.2). Encoded as a
 * <strong>lossless PNG</strong> ({@code image/png}) keeping the receipt pixels untouched.
 *
 * @param pngBytes           the rendered page bytes (lossless PNG)
 * @param whitenedBackground {@code true} when a document quad was detected and everything outside it
 *                           was painted white; {@code false} when the page fell back to its oriented
 *                           original (per-page detection failure)
 */
public record ProcessedPage(byte[] pngBytes, boolean whitenedBackground) {
}
