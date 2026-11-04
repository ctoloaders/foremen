package com.foremen.service.bank.ingest;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pure helper that turns the connector's normalized counterparty fields into ledger fields and the
 * {@code counterpartyKey} (FOR-07-04, Requirement 6.5).
 *
 * <p>The connector already resolves the payer (INCOME) or payee (EXPENSE) into
 * {@code counterpartyName} / {@code counterpartyIbanRaw}. This extractor:
 * <ul>
 *   <li>trims and collapses whitespace in the display name ({@code null} when blank);</li>
 *   <li>normalizes the IBAN with {@link IbanNormalizer}; an invalid IBAN yields
 *       {@code iban = null} and {@code ibanInvalid = true} so the caller keeps the raw value in
 *       {@code counterparty_raw};</li>
 *   <li>builds the key {@code IBAN:{iban}} when a valid IBAN exists, otherwise
 *       {@code NAME:{normalized name}} via {@link CounterpartyNameNormalizer}, otherwise
 *       {@code null}.</li>
 * </ul>
 */
public final class CounterpartyExtractor {

    public static final String IBAN_PREFIX = "IBAN:";
    public static final String NAME_PREFIX = "NAME:";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private CounterpartyExtractor() {
        // Utility class: no instances.
    }

    /**
     * Extracted counterparty.
     *
     * @param name trimmed display name, or {@code null}
     * @param iban normalized valid IBAN, or {@code null}
     * @param ibanInvalid {@code true} when a non-blank raw IBAN failed validation
     * @param key counterparty key ({@code IBAN:…} / {@code NAME:…}), or {@code null}
     */
    public record Counterparty(String name, String iban, boolean ibanInvalid, String key) {}

    /** Extracts the counterparty from the connector's normalized name and raw IBAN. */
    public static Counterparty extract(String counterpartyName, String counterpartyIbanRaw) {
        String name = displayName(counterpartyName);
        String cleaned = IbanNormalizer.clean(counterpartyIbanRaw);
        Optional<String> iban = IbanNormalizer.normalize(counterpartyIbanRaw);
        boolean invalid = cleaned != null && iban.isEmpty();
        return new Counterparty(name, iban.orElse(null), invalid, key(iban.orElse(null), name));
    }

    /**
     * Builds the counterparty key from an already normalized IBAN and a name.
     *
     * @return {@code IBAN:{iban}}, {@code NAME:{normalized}}, or {@code null} when neither exists
     */
    public static String key(String normalizedIban, String name) {
        if (normalizedIban != null && !normalizedIban.isBlank()) {
            return IBAN_PREFIX + normalizedIban;
        }
        String normalized = CounterpartyNameNormalizer.normalize(name);
        return normalized.isEmpty() ? null : NAME_PREFIX + normalized;
    }

    private static String displayName(String raw) {
        if (raw == null) {
            return null;
        }
        String collapsed = WHITESPACE.matcher(raw.strip()).replaceAll(" ");
        return collapsed.isEmpty() ? null : collapsed;
    }
}
