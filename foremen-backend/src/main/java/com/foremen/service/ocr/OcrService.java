package com.foremen.service.ocr;

/**
 * FOR-06-03 (Requirement 3, design §5.2) — the backend OCR seam: given a stored document's raw
 * bytes + MIME type, it produces the white-background rendition(s), extracts text via Google Cloud
 * Vision {@code documentTextDetection}, and parses the text into per-purchased-line structured
 * items via a Google LLM prompt, returning a complete {@link OcrResult}.
 *
 * <p>Two wirings exist so the application runs with or without the provider configured (R3.5):
 * <ul>
 *   <li>{@link GoogleOcrService} ({@code @ConditionalOnProperty foremen.ocr.enabled=true}) — the
 *       thin I/O orchestrator wrapping the pure {@link OcrHelpers} helpers over the Google Cloud
 *       Vision + Gen AI (Gemini) client libraries.</li>
 *   <li>{@link DisabledOcrService} (default / {@code foremen.ocr.enabled=false}) — {@link #isEnabled()}
 *       is {@code false} and {@link #process} throws {@link OcrUnavailableException}.</li>
 * </ul>
 *
 * <p>The result is never partial: {@link #process} either returns a complete {@link OcrResult} or
 * throws {@link OcrUnavailableException} (provider not configured) / {@link OcrFailedException}
 * (configured but this document could not be processed). Provider credentials and the model are read
 * from the {@code foremen.ocr.*} configuration block, never hardcoded (R3.6).
 */
public interface OcrService {

    /**
     * Whether the OCR provider is configured and enabled. When {@code false}, {@link #process} will
     * throw {@link OcrUnavailableException} and the orchestrating service leaves a freshly uploaded
     * document {@code UPLOADED} (R3.5).
     *
     * @return {@code true} when the Google OCR provider is wired and enabled
     */
    boolean isEnabled();

    /**
     * Produce the white-background rendition(s), OCR them, and parse per-line. Never partial:
     * returns a complete {@link OcrResult} or throws.
     *
     * @param rawBytes     the stored raw upload bytes (an image or a PDF)
     * @param mimeType     the source MIME type ({@code image/png}, {@code image/jpeg},
     *                     {@code image/webp} or {@code application/pdf})
     * @param keyNamespace an opaque per-document key namespace hint the caller may use when storing
     *                     the processed renditions (the service itself performs no storage I/O —
     *                     storing {@link OcrResult#pages()} is the orchestrator's job, task 5.1)
     * @return the complete OCR result: the processed rendition pages, the per-line items, and the
     *         document-level supplier/total
     * @throws OcrUnavailableException when the provider is not configured (disabled fallback, R3.5)
     * @throws OcrFailedException      when the provider is configured but this document could not be
     *                                 processed: a provider error, unparseable output, or no lines
     *                                 extracted (R3.4)
     */
    OcrResult process(byte[] rawBytes, String mimeType, String keyNamespace);
}
