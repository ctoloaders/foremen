package com.foremen.exception.dto;

import java.util.List;

/**
 * FOR-05-12 (Requirement 4; design Decision D4) — the {@code 409 Conflict} error body returned when
 * the activation readiness gate rejects a {@code pre-activation → ACTIVE} transition because the
 * project is not fully ready.
 *
 * <p>It carries a {@code message} — the localized top-level "project not ready" text resolved from
 * {@code error.project.not.ready} (Requirement 4.1) — and the not-ready {@code areas}: for each
 * readiness area whose state is not {@code DONE} (Requirement 4.2/4.3), its stable {@code key}, its
 * already-localized {@code name}, its {@code state} enum name, its {@code percentage}, and its
 * already-localized outstanding-item messages. Every human-readable string is resolved to the
 * request locale in {@code ForemenControllerAdvice#handleProjectNotReady} (Requirement 4.4), so the
 * client renders the breakdown without further resolution. Mirrors {@link WorkReportBatchItemError}.
 *
 * @param message the localized top-level not-ready message for the request locale
 * @param areas   the not-ready areas (state != {@code DONE}), each with localized strings
 */
public record ProjectNotReadyError(
        String message,
        List<NotReadyArea> areas) {

    /**
     * One not-ready area in the {@link ProjectNotReadyError} breakdown.
     *
     * @param key        the stable, lowercase area key
     * @param name       the already-localized area display name
     * @param state      the area's readiness state enum name ({@code PARTIAL} / {@code BLOCKED})
     * @param percentage the area's readiness percentage, {@code 0..100}
     * @param items      the ordered, already-localized outstanding-item messages
     */
    public record NotReadyArea(
            String key,
            String name,
            String state,
            int percentage,
            List<String> items) {
    }
}
