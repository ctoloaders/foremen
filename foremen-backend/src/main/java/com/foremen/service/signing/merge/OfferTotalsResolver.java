package com.foremen.service.signing.merge;

import org.springframework.stereotype.Component;

/**
 * FOR-05-08 (Requirement 3.3): resolves the offer-totals placeholder group — the approved offer's
 * net total (design §Components resolver table row {@code OfferTotalsResolver}).
 *
 * <table>
 *   <caption>Supported tokens</caption>
 *   <tr><th>Token</th><th>Source</th></tr>
 *   <tr><td>{@code {TotalBeforeTax}}</td><td>{@link MergeContext#approvedOfferNetTotal()} — the approved offer net total (before VAT)</td></tr>
 * </table>
 *
 * <p>The net total is pre-resolved onto the {@link MergeContext} by the service layer (reading the
 * project's {@code APPROVED} offer's {@code totalNet}); this resolver simply surfaces it. It is
 * {@code null} (unresolved) when the project has no approved offer, so the "total before tax"
 * placeholder is never silently rendered as blank or zero (Requirement 3.4, null→unresolved).
 */
@Component
public class OfferTotalsResolver implements MergeFieldResolver {

    static final String TOKEN_TOTAL_BEFORE_TAX = "TotalBeforeTax";

    @Override
    public boolean supports(String token) {
        return TOKEN_TOTAL_BEFORE_TAX.equals(token);
    }

    @Override
    public String resolve(String token, MergeContext ctx) {
        if (!TOKEN_TOTAL_BEFORE_TAX.equals(token)) {
            return null;
        }
        String total = ctx.approvedOfferNetTotal();
        return total == null || total.isBlank() ? null : total;
    }
}
