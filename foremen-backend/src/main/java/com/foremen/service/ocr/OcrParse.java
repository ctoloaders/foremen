package com.foremen.service.ocr;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-06-03 (Requirement 3.2, design §5.2) — the result of the pure
 * {@link OcrHelpers#parsePerLineResponse(String)} LLM-response parse: the structured per-line items
 * plus the document-level supplier name and total. It carries only the parse output; the
 * white-background rendition pages are produced by the I/O orchestrator (task 4.2) and combined with
 * this parse into an {@link OcrResult}.
 *
 * <p>{@code lines} is never {@code null} (it may be empty — an empty array drives the {@code FAILED}
 * "no lines extracted" transition, R3.4). {@code supplierName} and {@code documentTotal} are
 * {@code null} when the model did not report them.
 */
public record OcrParse(
        List<ParsedLine> lines,
        String supplierName,
        BigDecimal documentTotal) {
}
