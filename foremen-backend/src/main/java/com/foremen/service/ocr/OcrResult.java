package com.foremen.service.ocr;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-06-03 (Requirement 3, design §5.2) — the complete result of the OCR pipeline for one uploaded
 * document: the white-background rendition pages to store (R3.0), the per-purchased-item structured
 * lines (R3.2), and the document-level supplier name and total (nullable).
 *
 * <p>The {@code OcrService} either returns a complete {@code OcrResult} or throws — it is never
 * partial. An empty {@link #lines()} list is treated by the orchestrator as "no lines extracted" and
 * drives the {@code FAILED} transition (R3.4).
 */
public record OcrResult(
        List<ProcessedPage> pages,
        List<ParsedLine> lines,
        String supplierName,
        BigDecimal documentTotal) {
}
