package com.foremen.service.bank.link;

import com.foremen.dao.model.DocumentPaymentStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
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
 * Property 4 (FOR-07-04): status totality — {@link DocumentPaymentStatusDeriver} returns exactly one
 * {@link DocumentPaymentStatus} for every combination of a (possibly unknown) document total and
 * positive link amounts, following the design rule: none → {@code UNMATCHED}; Σ &lt; total (or total
 * unknown and Σ &gt; 0) → {@code PARTIALLY_PAID}; Σ ≥ total → {@code PAID}.
 *
 * <p>Feature: FOR-07-04-bank-ledger, Property 4: Status totality
 *
 * <p><b>Validates: Requirements 16.4, 18.3, 28.4</b>
 */
@Tag("Feature: FOR-07-04-bank-ledger, Property 4: Status totality")
class DocumentPaymentStatusDeriverPropertyTest {

    @Provide
    Arbitrary<List<BigDecimal>> linkAmounts() {
        return Arbitraries.longs().between(1, 100_000_000L)
                .map(cents -> BigDecimal.valueOf(cents, 2))
                .list().ofMaxSize(5);
    }

    @Provide
    Arbitrary<BigDecimal> totals() {
        // Known totals in 0..5_000_000.00; ~20% unknown (null).
        return Arbitraries.longs().between(0, 500_000_000L)
                .map(cents -> BigDecimal.valueOf(cents, 2))
                .injectNull(0.2);
    }

    private static BigDecimal sum(List<BigDecimal> amounts) {
        return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------------------------------
    // Property 4a: totality + the design rule for every (total, links) combination.
    // ------------------------------------------------------------------------------------------
    @Property(tries = 500)
    @Tag("Feature: FOR-07-04-bank-ledger, Property 4: Status totality")
    void derivesExactlyOneStatusPerRule(@ForAll("totals") BigDecimal total,
                                        @ForAll("linkAmounts") List<BigDecimal> links) {
        DocumentPaymentStatus status = DocumentPaymentStatusDeriver.derive(total, links);

        assertThat(status).isNotNull();
        DocumentPaymentStatus expected;
        if (links.isEmpty()) {
            expected = DocumentPaymentStatus.UNMATCHED;
        } else if (total != null && sum(links).compareTo(total) >= 0) {
            expected = DocumentPaymentStatus.PAID;
        } else {
            expected = DocumentPaymentStatus.PARTIALLY_PAID;
        }
        assertThat(status).isEqualTo(expected);
        // UNMATCHED ⇔ no links.
        assertThat(status == DocumentPaymentStatus.UNMATCHED).isEqualTo(links.isEmpty());
        // Unknown total never yields PAID.
        if (total == null) {
            assertThat(status).isNotEqualTo(DocumentPaymentStatus.PAID);
        }
        // Link order never matters.
        List<BigDecimal> reversed = new ArrayList<>(links);
        java.util.Collections.reverse(reversed);
        assertThat(DocumentPaymentStatusDeriver.derive(total, reversed)).isEqualTo(status);
    }

    // ------------------------------------------------------------------------------------------
    // Property 4b: monotonicity — adding a link never moves the status backwards.
    // ------------------------------------------------------------------------------------------
    @Property(tries = 300)
    @Tag("Feature: FOR-07-04-bank-ledger, Property 4: Status totality")
    void addingALinkNeverRegresses(@ForAll("totals") BigDecimal total,
                                   @ForAll("linkAmounts") List<BigDecimal> links,
                                   @ForAll @IntRange(min = 1, max = 1_000_000) int extraCents) {
        DocumentPaymentStatus before = DocumentPaymentStatusDeriver.derive(total, links);
        List<BigDecimal> more = new ArrayList<>(links);
        more.add(BigDecimal.valueOf(extraCents, 2));
        DocumentPaymentStatus after = DocumentPaymentStatusDeriver.derive(total, more);

        assertThat(after.ordinal()).isGreaterThanOrEqualTo(before.ordinal());
        assertThat(after).isNotEqualTo(DocumentPaymentStatus.UNMATCHED);
    }

    // ------------------------------------------------------------------------------------------
    // Examples per rule.
    // ------------------------------------------------------------------------------------------
    @Property(tries = 1)
    void examplesPerRule() {
        BigDecimal total = new BigDecimal("1230.00");
        assertThat(DocumentPaymentStatusDeriver.derive(total, List.of()))
                .isEqualTo(DocumentPaymentStatus.UNMATCHED);
        assertThat(DocumentPaymentStatusDeriver.derive(null, List.of()))
                .isEqualTo(DocumentPaymentStatus.UNMATCHED);
        assertThat(DocumentPaymentStatusDeriver.derive(total, List.of(new BigDecimal("1000.00"))))
                .isEqualTo(DocumentPaymentStatus.PARTIALLY_PAID);
        assertThat(DocumentPaymentStatusDeriver.derive(total,
                List.of(new BigDecimal("1000.00"), new BigDecimal("230"))))
                .isEqualTo(DocumentPaymentStatus.PAID);
        assertThat(DocumentPaymentStatusDeriver.derive(null, List.of(new BigDecimal("5000.00"))))
                .isEqualTo(DocumentPaymentStatus.PARTIALLY_PAID);
        // Scale-insensitive comparison: 1230 == 1230.00.
        assertThat(DocumentPaymentStatusDeriver.derive(new BigDecimal("1230"), List.of(total)))
                .isEqualTo(DocumentPaymentStatus.PAID);
        // Non-positive link amounts are rejected.
        assertThatThrownBy(() -> DocumentPaymentStatusDeriver.derive(total, List.of(BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentPaymentStatusDeriver.derive(total, List.of(new BigDecimal("-1"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
