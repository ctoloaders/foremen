package com.foremen.service.bank.link;

import com.foremen.dao.model.DetectionSource;
import com.foremen.dao.model.LinkStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * FOR-07-04 (Requirements 13.5, 17.5): pure derivation of a ledger line's {@link LinkStatus}.
 *
 * <p>The first rule that applies wins:
 * <ol>
 *   <li>{@code IGNORED} — the transaction is ignored;</li>
 *   <li>{@code LINKED} — Σ links = |amount| and every link is {@code MANUAL} or confirmed;</li>
 *   <li>{@code AUTO_LINKED} — Σ links = |amount| and at least one link is an unconfirmed automatic
 *       link ({@code RULE}; an unconfirmed {@code AI} link is treated the same way so the function
 *       stays total, although the service only auto-applies {@code RULE} links);</li>
 *   <li>{@code PARTIALLY_LINKED} — 0 &lt; Σ links &lt; |amount|;</li>
 *   <li>{@code SUGGESTED} — no links but at least one open suggestion;</li>
 *   <li>{@code UNLINKED} — otherwise.</li>
 * </ol>
 *
 * <p>Preconditions (violations throw {@link IllegalArgumentException}; the service rejects them
 * earlier with domain errors):
 * <ul>
 *   <li>every link amount is &gt; 0 and Σ links ≤ |amount| (R13.4, Property 3);</li>
 *   <li>an ignored transaction carries no links (Property 4: {@code IGNORED ⇒ no links}).</li>
 * </ul>
 *
 * <p>A zero-amount line with no links is {@code SUGGESTED} / {@code UNLINKED} (never {@code LINKED}),
 * because "linked" requires at least one link.
 */
public final class LinkStatusDeriver {

    /** The facts of one applied link the derivation needs. */
    public record LinkFact(BigDecimal amount, DetectionSource source, boolean confirmed) {
        public LinkFact {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(source, "source");
        }

        /** {@code MANUAL} links and confirmed links count as reviewed. */
        boolean reviewed() {
            return confirmed || source == DetectionSource.MANUAL;
        }
    }

    private LinkStatusDeriver() {
    }

    /**
     * @param ignored         whether the transaction is marked ignored
     * @param amount          the signed transaction amount (its absolute value is used)
     * @param links           the applied links (never {@code null}; may be empty)
     * @param openSuggestions the number of {@code OPEN} suggestions
     * @return exactly one status for every valid input
     */
    public static LinkStatus derive(boolean ignored, BigDecimal amount, List<LinkFact> links,
                                    int openSuggestions) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(links, "links");
        if (openSuggestions < 0) {
            throw new IllegalArgumentException("openSuggestions must be >= 0");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (LinkFact link : links) {
            Objects.requireNonNull(link, "link");
            if (link.amount().signum() <= 0) {
                throw new IllegalArgumentException("link amount must be > 0");
            }
            total = total.add(link.amount());
        }
        BigDecimal abs = amount.abs();
        if (total.compareTo(abs) > 0) {
            throw new IllegalArgumentException("sum of link amounts exceeds |amount|");
        }
        if (ignored) {
            if (!links.isEmpty()) {
                throw new IllegalArgumentException("an ignored transaction must not have links");
            }
            return LinkStatus.IGNORED;
        }
        if (!links.isEmpty() && total.compareTo(abs) == 0) {
            boolean allReviewed = links.stream().allMatch(LinkFact::reviewed);
            return allReviewed ? LinkStatus.LINKED : LinkStatus.AUTO_LINKED;
        }
        if (!links.isEmpty()) {
            return LinkStatus.PARTIALLY_LINKED;
        }
        return openSuggestions > 0 ? LinkStatus.SUGGESTED : LinkStatus.UNLINKED;
    }
}
