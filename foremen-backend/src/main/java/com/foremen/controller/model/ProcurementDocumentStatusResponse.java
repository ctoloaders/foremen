package com.foremen.controller.model;

import com.foremen.dao.model.ProcessingStatus;

/**
 * FOR-06-03 Amendment A1 (R16.1) — the lightweight status projection returned by
 * {@code GET /api/procurement-documents/{id}/status}, the cheap endpoint the client polls while a
 * document is processing (the "status then full dto" contract, A1 R16.3).
 *
 * <p>Deliberately minimal: it carries ONLY the lifecycle fields a poller needs — the document id,
 * the current {@code status}, the derived processing {@code stage}, and the stable
 * {@code failureReason} — and NONE of the heavy detail (no lines, no signed image URLs, no derived
 * names). This keeps per-row polling on the list cheap (A1 R16.1/R19.2).
 *
 * <p>{@code stage} equals {@code status} while the document is in a non-terminal processing stage
 * ({@link ProcessingStatus#isProcessing()}), and is {@code null} on a terminal state
 * ({@code PARSED}/{@code FAILED}) or the OCR-disabled {@code UPLOADED} resting state — so a client
 * can render a stage label iff {@code stage != null}.
 *
 * @param id            the document id
 * @param status        the current lifecycle status
 * @param stage         the current processing stage (== {@code status} while processing, else
 *                      {@code null})
 * @param failureReason the stable failure-reason code on {@code FAILED}, else {@code null}
 */
public record ProcurementDocumentStatusResponse(
        Long id,
        ProcessingStatus status,
        ProcessingStatus stage,
        String failureReason) {

    /**
     * Builds the status projection from the resolved lifecycle fields, deriving {@code stage} from
     * {@code status} ({@code stage == status} iff processing, else {@code null}).
     *
     * @param id            the document id
     * @param status        the current lifecycle status
     * @param failureReason the stable failure-reason code, or {@code null}
     * @return the status response with {@code stage} derived
     */
    public static ProcurementDocumentStatusResponse of(Long id, ProcessingStatus status,
                                                       String failureReason) {
        ProcessingStatus stage = status != null && status.isProcessing() ? status : null;
        return new ProcurementDocumentStatusResponse(id, status, stage, failureReason);
    }
}
