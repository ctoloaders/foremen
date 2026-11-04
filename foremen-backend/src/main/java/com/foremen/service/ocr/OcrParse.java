package com.foremen.service.ocr;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 *
 * <p>FOR-07-04 (Requirement 18.2) — also carries the invoice fields used by bank matching:
 * {@code supplierNip} (normalized 10 digits with a valid checksum, else {@code null}),
 * {@code invoiceNumber} (≤ 64 chars, else {@code null}) and {@code invoiceDate} (ISO date, else
 * {@code null}).
 */
public record OcrParse(
        List<ParsedLine> lines,
        String supplierName,
        BigDecimal documentTotal,
        String supplierNip,
        String invoiceNumber,
        LocalDate invoiceDate) {

    /** FOR-06-03 shape (no invoice fields) — kept for backward compatibility. */
    public OcrParse(List<ParsedLine> lines, String supplierName, BigDecimal documentTotal) {
        this(lines, supplierName, documentTotal, null, null, null);
    }
}
