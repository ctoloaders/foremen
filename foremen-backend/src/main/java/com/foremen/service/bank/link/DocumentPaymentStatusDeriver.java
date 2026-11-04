package com.foremen.service.bank.link;

import com.foremen.dao.model.DocumentPaymentStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * FOR-07-04 (Requirements 16.4, 18.3, 28.4): pure derivation of a procurement document's
 * {@link DocumentPaymentStatus} from its {@code PROCUREMENT} link amounts.
 *
 * <ol>
 *   <li>{@code UNMATCHED} — no links;</li>
 *   <li>{@code PAID} — the document total is known and Σ links ≥ total;</li>
 *   <li>{@code PARTIALLY_PAID} — otherwise: Σ links &lt; total, or the total is unknown
 *       ({@code null}, e.g. OCR not finished / failed) and Σ links &gt; 0.</li>
 * </ol>
 *
 * <p>The function is total over valid inputs. Σ links &gt; total is not rejected here (the link
 * service enforces the allocation bound, Property 3) and maps to {@code PAID}. Every link amount must
 * be &gt; 0 ({@link IllegalArgumentException} otherwise), consistent with {@link LinkStatusDeriver}.
 */
public final class DocumentPaymentStatusDeriver {

    private DocumentPaymentStatusDeriver() {
    }

    /**
     * @param documentTotal the document total, or {@code null} when unknown
     * @param linkAmounts   the amounts of the document's applied links (never {@code null}; may be empty)
     * @return exactly one status for every valid input
     */
    public static DocumentPaymentStatus derive(BigDecimal documentTotal, List<BigDecimal> linkAmounts) {
        Objects.requireNonNull(linkAmounts, "linkAmounts");
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal amount : linkAmounts) {
            Objects.requireNonNull(amount, "link amount");
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("link amount must be > 0");
            }
            sum = sum.add(amount);
        }
        if (linkAmounts.isEmpty()) {
            return DocumentPaymentStatus.UNMATCHED;
        }
        if (documentTotal != null && sum.compareTo(documentTotal) >= 0) {
            return DocumentPaymentStatus.PAID;
        }
        return DocumentPaymentStatus.PARTIALLY_PAID;
    }
}
