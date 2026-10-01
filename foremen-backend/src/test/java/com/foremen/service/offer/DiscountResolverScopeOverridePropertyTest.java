package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.DiscountScope;
import com.foremen.service.offer.DiscountResolver.DiscountInput;
import com.foremen.service.offer.DiscountResolver.EffectiveDiscount;
import com.foremen.service.offer.DiscountResolver.EstimateLineInput;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link DiscountResolver#resolveEffective(List, List)} — the pure,
 * deterministic <b>override-and-cancel</b> scope resolver (FOR-05-07, design §Property 3).
 *
 * <p>For every line the surviving discount is the one at the highest scope covering it: a
 * {@link DiscountScope#GLOBAL} discount supersedes the {@link DiscountScope#CATEGORY} and
 * {@link DiscountScope#LINE} discounts it covers, and a {@code CATEGORY} discount (matching the
 * line's {@code categoryId}) supersedes the line's own {@code LINE} discount. The resolver is a pure
 * function of the discount set: <b>independent of the order</b> the discounts are supplied and
 * <b>deterministic</b> (same input → same output). The tests exercise it directly with no
 * persistence, so Property 3 is cheap to run over 100+ iterations.
 *
 * <p>Feature: FOR-05-07-offer-approval, Property 3: Scope override-and-cancel is deterministic and
 * order-independent
 *
 * <p><b>Validates: Requirements 1.4, 2.5, 4.9, 10.16</b>
 */
@Tag("Feature: FOR-05-07-offer-approval, Property 3: Scope override-and-cancel is deterministic and order-independent")
class DiscountResolverScopeOverridePropertyTest {

    private final DiscountResolver resolver = new DiscountResolver();

    // ------------------------------------------------------------------------------------------
    // Property 3a: for each line the surviving discount is exactly the highest-scope one covering it
    // -- GLOBAL if any discount at GLOBAL exists, else the CATEGORY discount matching the line's
    // categoryId if one exists, else the line's own LINE discount if one exists, else no entry.
    // Validates: Requirements 1.4, 2.5, 4.9
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 3: Scope override-and-cancel is deterministic and order-independent")
    void survivingDiscountIsTheHighestScopeCoveringEachLine(@ForAll("scenarios") Scenario scenario) {
        Map<Long, EffectiveDiscount> result =
                resolver.resolveEffective(scenario.discounts(), scenario.lines());

        boolean hasGlobal = scenario.discounts().stream()
                .anyMatch(d -> d.scope() == DiscountScope.GLOBAL);

        for (EstimateLineInput line : scenario.lines()) {
            EffectiveDiscount effective = result.get(line.lineId());
            DiscountScope expectedScope = expectedSurvivingScope(scenario, line, hasGlobal);

            if (expectedScope == null) {
                assertThat(effective)
                        .as("line %d has no covering discount", line.lineId())
                        .isNull();
            } else {
                assertThat(effective)
                        .as("line %d must have a surviving discount", line.lineId())
                        .isNotNull();
                assertThat(effective.scope())
                        .as("surviving scope for line %d", line.lineId())
                        .isEqualTo(expectedScope);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 3b (the key property): resolving is INDEPENDENT of the order the discounts are
    // supplied. Shuffling the discount list yields an identical result map (Requirements 2.5 / 4.9 /
    // 10.16).
    // Validates: Requirements 2.5, 4.9, 10.16
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 3: Scope override-and-cancel is deterministic and order-independent")
    void resolutionIsIndependentOfDiscountOrder(@ForAll("scenarios") Scenario scenario,
                                                @ForAll long seed) {
        List<DiscountInput> original = scenario.discounts();
        List<DiscountInput> shuffled = new ArrayList<>(original);
        java.util.Collections.shuffle(shuffled, new java.util.Random(seed));

        Map<Long, EffectiveDiscount> fromOriginal = resolver.resolveEffective(original, scenario.lines());
        Map<Long, EffectiveDiscount> fromShuffled = resolver.resolveEffective(shuffled, scenario.lines());

        assertThat(fromShuffled)
                .as("resolving a shuffled discount list must yield an identical result map")
                .isEqualTo(fromOriginal);
    }

    // ------------------------------------------------------------------------------------------
    // Property 3c: the resolver is deterministic -- the same input always yields the same output.
    // Validates: Requirements 2.5, 10.16
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-07-offer-approval, Property 3: Scope override-and-cancel is deterministic and order-independent")
    void resolutionIsDeterministic(@ForAll("scenarios") Scenario scenario) {
        Map<Long, EffectiveDiscount> first = resolver.resolveEffective(scenario.discounts(), scenario.lines());
        Map<Long, EffectiveDiscount> second = resolver.resolveEffective(scenario.discounts(), scenario.lines());

        assertThat(second)
                .as("the resolver must be deterministic for identical inputs")
                .isEqualTo(first);
    }

    // ------------------------------------------------------------------------------------------
    // Oracle: the expected surviving scope for a line, computed independently of the resolver.
    // ------------------------------------------------------------------------------------------

    private DiscountScope expectedSurvivingScope(Scenario scenario, EstimateLineInput line,
                                                 boolean hasGlobal) {
        if (hasGlobal) {
            return DiscountScope.GLOBAL;
        }
        if (line.categoryId() != null && scenario.categoryTargets().contains(line.categoryId())) {
            return DiscountScope.CATEGORY;
        }
        if (scenario.lineTargets().contains(line.lineId())) {
            return DiscountScope.LINE;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * A generated scenario: a set of lines (each with an optional category) plus an arbitrary mix of
     * GLOBAL / CATEGORY / LINE discounts, some of which cover the generated lines and some of which
     * target ids that do not exist (to exercise non-covering discounts).
     */
    record Scenario(List<EstimateLineInput> lines, List<DiscountInput> discounts,
                    java.util.Set<Long> categoryTargets, java.util.Set<Long> lineTargets) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        // A small pool of category ids so CATEGORY discounts can actually cover lines.
        Arbitrary<List<Long>> categoryPool = Arbitraries.longs().between(1L, 5L).list()
                .ofMinSize(1).ofMaxSize(5).uniqueElements();

        return categoryPool.flatMap(categories -> {
            Arbitrary<EstimateLineInput> lineArb = Combinators.combine(
                    Arbitraries.longs().between(1L, 40L),
                    Arbitraries.of(true, false),
                    Arbitraries.of(categories.toArray(new Long[0])),
                    Arbitraries.bigDecimals().between(BigDecimal.ZERO, BigDecimal.valueOf(10_000))
                            .ofScale(2))
                    .as((lineId, hasCategory, category, netBase) ->
                            new EstimateLineInput(lineId, hasCategory ? category : null, netBase));

            Arbitrary<List<EstimateLineInput>> linesArb = lineArb.list()
                    .ofMinSize(1).ofMaxSize(12)
                    .uniqueElements(EstimateLineInput::lineId);

            return linesArb.flatMap(lines -> {
                Long[] lineIds = lines.stream().map(EstimateLineInput::lineId).toArray(Long[]::new);
                Long[] categoryIds = categories.toArray(new Long[0]);

                Arbitrary<DiscountInput> discountArb = Arbitraries.oneOf(
                        globalDiscount(),
                        categoryDiscount(categoryIds),
                        lineDiscount(lineIds));

                Arbitrary<List<DiscountInput>> discountsArb = discountArb.list()
                        .ofMinSize(0).ofMaxSize(16);

                return discountsArb.map(discounts -> {
                    java.util.Set<Long> categoryTargets = new java.util.HashSet<>();
                    java.util.Set<Long> lineTargets = new java.util.HashSet<>();
                    for (DiscountInput d : discounts) {
                        if (d.scope() == DiscountScope.CATEGORY && d.targetId() != null) {
                            categoryTargets.add(d.targetId());
                        } else if (d.scope() == DiscountScope.LINE && d.targetId() != null) {
                            lineTargets.add(d.targetId());
                        }
                    }
                    return new Scenario(new ArrayList<>(lines), new ArrayList<>(discounts),
                            categoryTargets, lineTargets);
                });
            });
        });
    }

    private Arbitrary<DiscountInput> globalDiscount() {
        return Combinators.combine(kinds(), values())
                .as((kind, value) -> new DiscountInput(DiscountScope.GLOBAL, null, kind, value));
    }

    private Arbitrary<DiscountInput> categoryDiscount(Long[] categoryIds) {
        return Combinators.combine(Arbitraries.of(categoryIds), kinds(), values())
                .as((target, kind, value) ->
                        new DiscountInput(DiscountScope.CATEGORY, target, kind, value));
    }

    private Arbitrary<DiscountInput> lineDiscount(Long[] lineIds) {
        return Combinators.combine(Arbitraries.of(lineIds), kinds(), values())
                .as((target, kind, value) ->
                        new DiscountInput(DiscountScope.LINE, target, kind, value));
    }

    private Arbitrary<DiscountKind> kinds() {
        return Arbitraries.of(DiscountKind.PERCENT, DiscountKind.ABSOLUTE);
    }

    private Arbitrary<BigDecimal> values() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, BigDecimal.valueOf(500)).ofScale(2);
    }
}
