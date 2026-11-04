package com.foremen.controller.model;

/**
 * FOR-07-03 (Requirement 5.6, 5.8) - result of a relink: the updated document row plus the counts.
 *
 * @param document     the updated document row with per-Link_Status counts
 * @param matchedLines lines that are plan-matched ({@code AUTO} / {@code MANUAL_MATCHED}) after the relink
 * @param resetLines   foreign {@code MANUAL_MATCHED} lines reset to {@code UNMATCHED} by a project change
 */
public record CompanyRelinkResponse(CompanyDocumentRowDto document, int matchedLines, int resetLines) {
}
