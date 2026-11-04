package com.foremen.service.procurement;

import java.util.Collection;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;

/**
 * FOR-07-03 (Requirements 3.3, 3.4) — pure helper that propagates a document-level expense category
 * onto its lines while preserving the FOR-06-04 R1.3 invariant
 * ({@code unplannedCategory != null ⟹ matchedMaterialId == null}).
 *
 * <ul>
 *   <li>{@link #apply}: sets the category on lines whose {@code matchStatus == UNMATCHED} and that
 *       carry no category yet. {@code AUTO}/{@code MANUAL_MATCHED} lines and manually reconciled
 *       {@code MANUAL_UNMATCHED} lines are never touched.</li>
 *   <li>{@link #clearOnMatched}: drops the category from lines that are matched
 *       ({@code AUTO}/{@code MANUAL_MATCHED}); unmatched lines keep theirs.</li>
 * </ul>
 */
public final class ExpenseCategoryApplier {

    private ExpenseCategoryApplier() {
    }

    /**
     * Applies {@code category} to every uncategorised {@code UNMATCHED} line.
     *
     * @return the number of lines changed (0 when {@code category} or {@code lines} is null)
     */
    public static int apply(UnplannedExpenseCategoryEntity category, Collection<ProcurementLineEntity> lines) {
        if (category == null || lines == null) {
            return 0;
        }
        int changed = 0;
        for (ProcurementLineEntity line : lines) {
            if (line.getMatchStatus() == MatchStatus.UNMATCHED && line.getUnplannedCategory() == null) {
                line.setUnplannedCategory(category);
                changed++;
            }
        }
        return changed;
    }

    /**
     * Clears the category on lines that are now matched.
     *
     * @return the number of lines changed
     */
    public static int clearOnMatched(Collection<ProcurementLineEntity> lines) {
        if (lines == null) {
            return 0;
        }
        int changed = 0;
        for (ProcurementLineEntity line : lines) {
            boolean matched = line.getMatchStatus() == MatchStatus.AUTO
                    || line.getMatchStatus() == MatchStatus.MANUAL_MATCHED;
            if (matched && line.getUnplannedCategory() != null) {
                line.setUnplannedCategory(null);
                changed++;
            }
        }
        return changed;
    }
}
