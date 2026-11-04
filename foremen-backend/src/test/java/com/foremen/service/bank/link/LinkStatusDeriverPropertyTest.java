package com.foremen.service.bank.link;

import com.foremen.dao.model.DetectionSource;
import com.foremen.dao.model.LinkStatus;
import com.foremen.service.bank.link.LinkStatusDeriver.LinkFact;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property 4 (FOR-07-04): status totality — {@link LinkStatusDeriver} returns exactly one status for
 * every valid combination, follows the first-applicable rule of R13.5, and {@code IGNORED ⇒ no links};
 * plus the Σ links ≤ |amount| precondition (Property 3 at the pure level).
 *
 * <p>Feature: FOR-07-04-bank-ledger, Property 4: Status totality
 *
 * <p><b>Validates: Requirements 13.5, 17.5</b>
 */
@Tag("Feature: FOR-07-04-bank-ledger, Property 4: Status totality")
class LinkStatusDeriverPropertyTest {

    /** A generated valid input: a signed amount and links whose Σ ≤ |amount|. */
    record Input(BigDecimal amount, List<LinkFact> links) {
        BigDecimal total() {
            return links.stream().map(LinkFact::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    @Provide
    Arbitrary<Input> validInputs() {
        // Amounts in grosze: |amount| in 0..1_000_000.00, any sign.
        Arbitrary<Long> absCents = Arbitraries.longs().between(0, 100_000_000L);
        Arbitrary<Boolean> negative = Arbitraries.of(true, false);
        Arbitrary<List<Long>> weights = Arbitraries.longs().between(1, 1_000).list().ofMaxSize(4);
        Arbitrary<Boolean> fullyAllocated = Arbitraries.of(true, false);
        Arbitrary<List<DetectionSource>> sources = Arbitraries.of(DetectionSource.class).list().ofSize(4);
        Arbitrary<List<Boolean>> confirmed = Arbitraries.of(true, false).list().ofSize(4);
        return Combinators.combine(absCents, negative, weights, fullyAllocated, sources, confirmed)
                .as(LinkStatusDeriverPropertyTest::buildInput);
    }

    private static Input buildInput(long absCents, boolean negative, List<Long> weights,
                                    boolean fullyAllocated, List<DetectionSource> sources,
                                    List<Boolean> confirmed) {
        BigDecimal amount = BigDecimal.valueOf(negative ? -absCents : absCents, 2);
        // Budget to split across links: all of |amount| or a strict part of it.
        long budget = fullyAllocated ? absCents : absCents / 2;
        List<LinkFact> links = new ArrayList<>();
        if (budget >= weights.size() && !weights.isEmpty()) {
            long weightSum = weights.stream().mapToLong(Long::longValue).sum();
            long allocated = 0;
            for (int i = 0; i < weights.size(); i++) {
                long cents = i == weights.size() - 1
                        ? budget - allocated
                        : Math.max(1, budget * weights.get(i) / weightSum);
                if (cents <= 0 || allocated + cents > budget) {
                    break;
                }
                allocated += cents;
                links.add(new LinkFact(BigDecimal.valueOf(cents, 2), sources.get(i), confirmed.get(i)));
            }
        }
        return new Input(amount, links);
    }

    private static LinkStatus expected(boolean ignored, Input in, int openSuggestions) {
        if (ignored) {
            return LinkStatus.IGNORED;
        }
        BigDecimal total = in.total();
        BigDecimal abs = in.amount().abs();
        if (!in.links().isEmpty() && total.compareTo(abs) == 0) {
            boolean allReviewed = in.links().stream()
                    .allMatch(l -> l.confirmed() || l.source() == DetectionSource.MANUAL);
            return allReviewed ? LinkStatus.LINKED : LinkStatus.AUTO_LINKED;
        }
        if (total.signum() > 0) {
            return LinkStatus.PARTIALLY_LINKED;
        }
        return openSuggestions > 0 ? LinkStatus.SUGGESTED : LinkStatus.UNLINKED;
    }

    // ------------------------------------------------------------------------------------------
    // Property 4a: totality + first-applicable rule of R13.5 for every valid non-ignored input.
    // ------------------------------------------------------------------------------------------
    @Property(tries = 500)
    @Tag("Feature: FOR-07-04-bank-ledger, Property 4: Status totality")
    void derivesExactlyOneStatusPerRule(@ForAll("validInputs") Input in,
                                        @ForAll @IntRange(min = 0, max = 3) int openSuggestions) {
        LinkStatus status = LinkStatusDeriver.derive(false, in.amount(), in.links(), openSuggestions);

        assertThat(status).isNotNull().isNotEqualTo(LinkStatus.IGNORED);
        assertThat(status).isEqualTo(expected(false, in, openSuggestions));
        // Link-bearing statuses ⇔ links present.
        boolean linkBearing = status == LinkStatus.LINKED || status == LinkStatus.AUTO_LINKED
                || status == LinkStatus.PARTIALLY_LINKED;
        assertThat(linkBearing).isEqualTo(!in.links().isEmpty());
        // Sign of the amount never matters.
        assertThat(LinkStatusDeriver.derive(false, in.amount().negate(), in.links(), openSuggestions))
                .isEqualTo(status);
    }

    // ------------------------------------------------------------------------------------------
    // Property 4b: IGNORED wins over suggestions and is only valid without links (IGNORED ⇒ no links).
    // ------------------------------------------------------------------------------------------
    @Property(tries = 300)
    @Tag("Feature: FOR-07-04-bank-ledger, Property 4: Status totality")
    void ignoredImpliesNoLinks(@ForAll("validInputs") Input in,
                               @ForAll @IntRange(min = 0, max = 3) int openSuggestions) {
        assertThat(LinkStatusDeriver.derive(true, in.amount(), List.of(), openSuggestions))
                .isEqualTo(LinkStatus.IGNORED);
        if (!in.links().isEmpty()) {
            assertThatThrownBy(() -> LinkStatusDeriver.derive(true, in.amount(), in.links(), openSuggestions))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 4c: the Σ links ≤ |amount| precondition — any over-allocation is rejected.
    // ------------------------------------------------------------------------------------------
    @Property(tries = 300)
    @Tag("Feature: FOR-07-04-bank-ledger, Property 4: Status totality")
    void overAllocationIsRejected(@ForAll("validInputs") Input in,
                                  @ForAll @IntRange(min = 1, max = 10_000) int extraCents,
                                  @ForAll DetectionSource source,
                                  @ForAll boolean ignored) {
        BigDecimal remaining = in.amount().abs().subtract(in.total());
        List<LinkFact> over = new ArrayList<>(in.links());
        over.add(new LinkFact(remaining.add(BigDecimal.valueOf(extraCents, 2)), source, true));

        assertThatThrownBy(() -> LinkStatusDeriver.derive(ignored, in.amount(), over, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------
    // Examples for each rule (R13.5).
    // ------------------------------------------------------------------------------------------
    @Property(tries = 1)
    void examplesPerRule() {
        BigDecimal amount = new BigDecimal("-300.00");
        LinkFact manualFull = new LinkFact(new BigDecimal("300.00"), DetectionSource.MANUAL, false);
        LinkFact ruleFull = new LinkFact(new BigDecimal("300.00"), DetectionSource.RULE, false);
        LinkFact ruleConfirmed = new LinkFact(new BigDecimal("300.00"), DetectionSource.RULE, true);
        LinkFact partial = new LinkFact(new BigDecimal("100.00"), DetectionSource.MANUAL, false);

        assertThat(LinkStatusDeriver.derive(true, amount, List.of(), 2)).isEqualTo(LinkStatus.IGNORED);
        assertThat(LinkStatusDeriver.derive(false, amount, List.of(manualFull), 0)).isEqualTo(LinkStatus.LINKED);
        assertThat(LinkStatusDeriver.derive(false, amount, List.of(ruleConfirmed), 0)).isEqualTo(LinkStatus.LINKED);
        assertThat(LinkStatusDeriver.derive(false, amount, List.of(ruleFull), 1)).isEqualTo(LinkStatus.AUTO_LINKED);
        assertThat(LinkStatusDeriver.derive(false, amount, List.of(partial), 1))
                .isEqualTo(LinkStatus.PARTIALLY_LINKED);
        assertThat(LinkStatusDeriver.derive(false, amount, List.of(), 1)).isEqualTo(LinkStatus.SUGGESTED);
        assertThat(LinkStatusDeriver.derive(false, amount, List.of(), 0)).isEqualTo(LinkStatus.UNLINKED);
        // Scale-insensitive comparison: 300 == 300.00.
        assertThat(LinkStatusDeriver.derive(false, new BigDecimal("300"), List.of(manualFull), 0))
                .isEqualTo(LinkStatus.LINKED);
        // Non-positive link amounts are rejected.
        assertThatThrownBy(() -> LinkStatusDeriver.derive(false, amount,
                List.of(new LinkFact(BigDecimal.ZERO, DetectionSource.MANUAL, false)), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
