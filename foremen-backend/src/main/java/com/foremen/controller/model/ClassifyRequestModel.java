package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

/**
 * FOR-06-04 (Requirement 3.1; design §5.3, §5.4) — the request body of the unplanned-expense
 * classify endpoint ({@code PUT /api/procurement-lines/{id}/classify}).
 *
 * <p>Carries the {@code UnplannedExpenseCategory} id to tag a deliberately unmatched line with. The
 * service verifies the category exists and is {@code active} (else
 * {@code error.purchases.classify.unknownCategory}) and rejects a matched line
 * ({@code error.purchases.classify.matchedLine}) before setting the line to {@code MANUAL_UNMATCHED}
 * with the category. Clearing a classification is the parameterless
 * {@code DELETE /{id}/classify} endpoint and takes no body.
 *
 * @param categoryId the unplanned-expense category id to classify the line with (mandatory)
 */
public record ClassifyRequestModel(@NotNull Long categoryId) {
}
