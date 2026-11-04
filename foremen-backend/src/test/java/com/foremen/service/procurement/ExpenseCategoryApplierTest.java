package com.foremen.service.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * Unit + property tests for {@link ExpenseCategoryApplier} (FOR-07-03, Requirements 3.3, 3.4).
 *
 * <p><b>Validates: Requirements 3.3, 3.4</b>
 */
class ExpenseCategoryApplierTest {

    private static ProcurementLineEntity line(MatchStatus status) {
        ProcurementLineEntity l = new ProcurementLineEntity();
        l.setMatchStatus(status);
        boolean matched = status == MatchStatus.AUTO || status == MatchStatus.MANUAL_MATCHED;
        l.setMatchedMaterialId(matched ? 1L : null);
        l.setMatchTier(matched ? MatchTier.EXACT : MatchTier.NONE);
        return l;
    }

    @Test
    void appliesOnlyToUnmatchedLines() {
        UnplannedExpenseCategoryEntity cat = new UnplannedExpenseCategoryEntity();
        List<ProcurementLineEntity> lines = List.of(
                line(MatchStatus.UNMATCHED), line(MatchStatus.AUTO),
                line(MatchStatus.MANUAL_MATCHED), line(MatchStatus.MANUAL_UNMATCHED));

        int changed = ExpenseCategoryApplier.apply(cat, lines);

        assertThat(changed).isEqualTo(1);
        assertThat(lines.get(0).getUnplannedCategory()).isSameAs(cat);
        assertThat(lines.get(1).getUnplannedCategory()).isNull();
        assertThat(lines.get(2).getUnplannedCategory()).isNull();
        assertThat(lines.get(3).getUnplannedCategory()).isNull();
    }

    @Test
    void doesNotOverwriteExistingCategoryAndNullCategoryIsNoop() {
        UnplannedExpenseCategoryEntity existing = new UnplannedExpenseCategoryEntity();
        UnplannedExpenseCategoryEntity other = new UnplannedExpenseCategoryEntity();
        ProcurementLineEntity l = line(MatchStatus.UNMATCHED);
        l.setUnplannedCategory(existing);

        assertThat(ExpenseCategoryApplier.apply(other, List.of(l))).isZero();
        assertThat(l.getUnplannedCategory()).isSameAs(existing);
        assertThat(ExpenseCategoryApplier.apply(null, List.of(l))).isZero();
    }

    @Test
    void clearsCategoryOnMatchedLinesOnly() {
        UnplannedExpenseCategoryEntity cat = new UnplannedExpenseCategoryEntity();
        ProcurementLineEntity matched = line(MatchStatus.AUTO);
        matched.setUnplannedCategory(cat);
        ProcurementLineEntity unmatched = line(MatchStatus.UNMATCHED);
        unmatched.setUnplannedCategory(cat);

        assertThat(ExpenseCategoryApplier.clearOnMatched(List.of(matched, unmatched))).isEqualTo(1);
        assertThat(matched.getUnplannedCategory()).isNull();
        assertThat(unmatched.getUnplannedCategory()).isSameAs(cat);
    }

    @Provide
    Arbitrary<List<MatchStatus>> statuses() {
        return Arbitraries.of(MatchStatus.values()).list().ofMaxSize(20);
    }

    /** Line invariant holds after apply; manual lines and matched lines are untouched. */
    @Property
    void invariantHoldsAndManualLinesUntouched(@ForAll("statuses") List<MatchStatus> statuses) {
        UnplannedExpenseCategoryEntity cat = new UnplannedExpenseCategoryEntity();
        List<ProcurementLineEntity> lines = new ArrayList<>();
        statuses.forEach(s -> lines.add(line(s)));

        ExpenseCategoryApplier.apply(cat, lines);
        ExpenseCategoryApplier.clearOnMatched(lines);

        for (ProcurementLineEntity l : lines) {
            if (l.getUnplannedCategory() != null) {
                assertThat(l.getMatchedMaterialId()).isNull();
            }
            if (l.getMatchStatus() == MatchStatus.UNMATCHED) {
                assertThat(l.getUnplannedCategory()).isSameAs(cat);
            } else {
                assertThat(l.getUnplannedCategory()).isNull();
            }
        }
    }
}
