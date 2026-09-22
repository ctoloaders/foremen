package com.foremen.service.pricing;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link MaxCollapseRule} (FOR-05-04, Property 1 — "MAX-collapse
 * correctness").
 *
 * <p>{@link MaxCollapseRule#collapseToSingle(java.util.Collection)} is a pure, deterministic
 * function of a work item's per-package values: for any non-empty collection, the surviving
 * collapsed value equals the MAX of the per-package values (Requirement 1.5, 7.3, 8.3). These
 * tests recompute the expected maximum independently (an oracle via {@link Collections#max}) and
 * additionally assert the survivor is one of the original values (never synthesized) and is
 * {@code >=} every element.
 *
 * <p>No Spring context and no database — the rule is exercised via {@code new
 * MaxCollapseRule()} over in-memory {@link BigDecimal} collections.
 *
 * <p>Feature: FOR-05-04-estimate-packages-changes, Property 1
 *
 * <p><b>Validates: Requirements 1.5, 7.3, 8.3</b>
 */
@Tag("Feature: FOR-05-04-estimate-packages-changes, Property 1")
class MaxCollapseRulePropertyTest {

    private final MaxCollapseRule rule = new MaxCollapseRule();

    // ------------------------------------------------------------------------------------------
    // Property 1a: the collapsed value equals Collections.max of the input collection (oracle
    // cross-check), is present in the original collection (never synthesized), and is >= every
    // element.
    // Validates: Requirements 1.5, 7.3, 8.3
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 1")
    void collapsedValueEqualsMaxIsMemberAndDominatesAllElements(
            @ForAll("nonEmptyValueCollections") List<BigDecimal> perPackageValues) {
        BigDecimal result = rule.collapseToSingle(perPackageValues);

        // Oracle: Collections.max on a copy, independent of the rule's own reduction logic.
        BigDecimal expectedMax = Collections.max(new ArrayList<>(perPackageValues));
        assertThat(result).isEqualByComparingTo(expectedMax);

        // The survivor is one of the original per-package rows, not an interpolated value.
        assertThat(perPackageValues).anyMatch(v -> v.compareTo(result) == 0);

        // The survivor dominates every element in the collection.
        assertThat(perPackageValues).allMatch(v -> result.compareTo(v) >= 0);
    }

    // ------------------------------------------------------------------------------------------
    // Property 1b: edge case — a single-element collection collapses to that element.
    // Validates: Requirement 1.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 1")
    void singleElementCollectionReturnsThatElement(@ForAll("anyValue") BigDecimal value) {
        BigDecimal result = rule.collapseToSingle(List.of(value));

        assertThat(result).isEqualByComparingTo(value);
    }

    // ------------------------------------------------------------------------------------------
    // Property 1c: edge case — duplicate MAX values still resolve to that (shared) maximum.
    // Validates: Requirements 1.5, 7.3
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 1")
    void duplicateMaxValuesResolveToTheSharedMaximum(
            @ForAll("nonEmptyValueCollections") List<BigDecimal> perPackageValues,
            @ForAll("anyValue") BigDecimal extraMax) {
        BigDecimal trueMax = Collections.max(new ArrayList<>(perPackageValues))
                .max(extraMax);

        List<BigDecimal> withDuplicateMax = new ArrayList<>(perPackageValues);
        withDuplicateMax.add(trueMax);
        withDuplicateMax.add(trueMax); // duplicate the max value

        BigDecimal result = rule.collapseToSingle(withDuplicateMax);

        assertThat(result).isEqualByComparingTo(trueMax);
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<BigDecimal> anyValue() {
        return Arbitraries.longs().between(0, 9_999_999)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }

    @Provide
    Arbitrary<List<BigDecimal>> nonEmptyValueCollections() {
        return anyValue().list().ofMinSize(1).ofMaxSize(12);
    }
}
