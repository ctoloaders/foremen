package com.foremen.service.ocr;

import java.math.BigDecimal;

/**
 * FOR-06-03 (Requirement 3.2, design §5.2) — one purchased item parsed from the OCR text by the LLM
 * per-line parse. This is a pure value carrier produced by
 * {@link OcrHelpers#parsePerLineResponse(String)} and, later, persisted one-per-row as a
 * {@code ProcurementLineEntity} by the service orchestrator (task 5.1).
 *
 * <p>{@code rawName} is always non-blank (objects with no usable name are dropped by the parser).
 * The numeric amounts ({@code quantity}, {@code unitPrice}, {@code lineTotal}) are
 * {@code abs()}-normalized to non-negative values and are {@code null} when absent. {@code unitHint}
 * and {@code manufacturerHint} are verbatim OCR text (R10.1) and nullable.
 */
public record ParsedLine(
        String rawName,
        BigDecimal quantity,
        String unitHint,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String manufacturerHint) {
}
