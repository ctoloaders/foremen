package com.foremen.service.execution.marginmatrix;

import java.math.BigDecimal;

/**
 * FOR-06-06 (design §Data Models) — one bucket of the project-level indirect-cost breakdown (R3.2):
 * the {@code Σ ProcurementLine.lineTotal} over the unlinked/unplanned lines carrying a given
 * {@code UnplannedExpenseCategory}, plus a single <em>uncategorized</em> bucket (a {@code null}
 * {@link #categoryId} with a localized name) for unmatched lines that carry no category.
 *
 * <p>Indirect cost is a project-level figure excluded from every per-work-item margin and included
 * only in the overall margin (R3.3, R3.4).
 *
 * @param categoryId   the {@code UnplannedExpenseCategory} id, or {@code null} for the uncategorized bucket
 * @param categoryName the category's localized name (the localized "uncategorized" label for the bucket)
 * @param amount       {@code Σ lineTotal} over the bucket's lines
 */
public record IndirectCategoryDto(
        Long categoryId,
        String categoryName,
        BigDecimal amount) {
}
