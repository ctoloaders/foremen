package com.foremen.service.bank.account;

import com.foremen.dao.model.HolderMatch;
import com.foremen.service.bank.detect.NameSimilarity;
import com.foremen.service.bank.ingest.CounterpartyNameNormalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Pure account holder verification (FOR-07-04, Requirement 3.7).
 *
 * <p>Compares the provider-reported holder (the account-level {@code holderName} plus the
 * connection-level holder info names / tax ids) with the company profile ({@code fullName},
 * {@code nip}):
 * <ol>
 *   <li>no company profile (blank name and blank NIP), or no holder data at all → {@code UNKNOWN};</li>
 *   <li>any holder tax id equal to the company NIP (digits only, last 10) → {@code MATCH};</li>
 *   <li>any holder name (account-level first, then connection-level) whose normalized token
 *       similarity with the company name is ≥ the threshold → {@code MATCH};</li>
 *   <li>otherwise → {@code MISMATCH}.</li>
 * </ol>
 * Names are normalized with {@link CounterpartyNameNormalizer} (case, diacritics and legal-form
 * suffixes ignored) and compared with {@link NameSimilarity#jaccard(Set, Set)}.
 */
public final class HolderMatcher {

    /** Default {@code holder.name-similarity} threshold. */
    public static final double DEFAULT_NAME_SIMILARITY = 0.8;

    private static final int NIP_LENGTH = 10;

    private HolderMatcher() {
        // Utility class: no instances.
    }

    /** {@link #match(Collection, Collection, String, String, String, double)} at the default threshold. */
    public static HolderMatch match(Collection<String> holderNames, Collection<String> holderTaxIds,
                                    String accountHolderName, String companyFullName, String companyNip) {
        return match(holderNames, holderTaxIds, accountHolderName, companyFullName, companyNip,
                DEFAULT_NAME_SIMILARITY);
    }

    /**
     * Verifies the holder against the company.
     *
     * @param holderNames connection-level holder names, may be {@code null}
     * @param holderTaxIds connection-level holder tax ids, may be {@code null}
     * @param accountHolderName account-level holder name, may be {@code null}
     * @param companyFullName company legal name, may be {@code null}
     * @param companyNip company NIP in any formatting, may be {@code null}
     * @param threshold minimum token similarity for a name match, in {@code (0, 1]}
     * @return the holder match, never {@code null}
     */
    public static HolderMatch match(Collection<String> holderNames, Collection<String> holderTaxIds,
                                    String accountHolderName, String companyFullName, String companyNip,
                                    double threshold) {
        List<String> names = new ArrayList<>();
        if (!isBlank(accountHolderName)) {
            names.add(accountHolderName);
        }
        if (holderNames != null) {
            holderNames.stream().filter(n -> !isBlank(n)).forEach(names::add);
        }
        List<String> taxIds = new ArrayList<>();
        if (holderTaxIds != null) {
            holderTaxIds.stream().map(HolderMatcher::normalizeNip).filter(t -> !t.isEmpty()).forEach(taxIds::add);
        }
        String nip = normalizeNip(companyNip);
        boolean noCompany = isBlank(companyFullName) && nip.isEmpty();
        if (noCompany || (names.isEmpty() && taxIds.isEmpty())) {
            return HolderMatch.UNKNOWN;
        }
        if (!nip.isEmpty() && taxIds.contains(nip)) {
            return HolderMatch.MATCH;
        }
        Set<String> companyTokens = CounterpartyNameNormalizer.tokens(companyFullName);
        if (!companyTokens.isEmpty()) {
            for (String name : names) {
                if (NameSimilarity.jaccard(CounterpartyNameNormalizer.tokens(name), companyTokens) >= threshold) {
                    return HolderMatch.MATCH;
                }
            }
        }
        return HolderMatch.MISMATCH;
    }

    /** Digits only, last 10 (drops a {@code PL} prefix, dashes and spaces); empty when no digits. */
    static String normalizeNip(String raw) {
        if (raw == null) {
            return "";
        }
        String digits = raw.replaceAll("\\D", "");
        return digits.length() > NIP_LENGTH ? digits.substring(digits.length() - NIP_LENGTH) : digits;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
