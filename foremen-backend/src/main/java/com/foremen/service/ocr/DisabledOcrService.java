package com.foremen.service.ocr;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * FOR-06-03 (Requirement 3.5, design §5.2) — the default {@link OcrService} wiring when the OCR
 * provider is not configured: active when {@code foremen.ocr.enabled} is {@code false} or absent
 * ({@code matchIfMissing = true}), so the application starts and runs normally without the Vision /
 * LLM provider configured.
 *
 * <p>{@link #isEnabled()} is {@code false} and {@link #process} throws
 * {@link OcrUnavailableException}. The orchestrating {@code ProcurementDocumentService} (task 5.1)
 * uses this to leave a freshly uploaded document {@code UPLOADED} (OCR never attempted) and to mark
 * it {@code FAILED} only on an explicit re-process request, with no processed rendition and no lines
 * extracted (R3.5). Mirrors {@link com.foremen.service.image.DisabledImageStorage}.
 */
@Component
@ConditionalOnProperty(prefix = "foremen.ocr", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledOcrService implements OcrService {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public OcrResult process(byte[] rawBytes, String mimeType, String keyNamespace,
                             OcrStageListener listener) {
        throw new OcrUnavailableException(
                "OCR provider is not configured (foremen.ocr.enabled is false or absent)");
    }
}
