package com.foremen.service;

import com.foremen.exception.ForemenApiException;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure IBAN normalizer and validator (Requirement 4 criterion 1, FOR-11-06).
 *
 * <p>Normalization removes spaces and hyphens and upper-cases the value. A value of exactly 26 digits
 * (the Polish NRB written without the country prefix) is read as a Polish account and gets the
 * {@code PL} prefix. The normalized value is valid when it matches
 * {@code ^[A-Z]{2}\d{2}[A-Z0-9]{11,30}$}, its length equals the country length from the table (for
 * countries in the table) and the ISO 7064 mod-97-10 remainder is 1.
 */
public final class IbanValidator {

    /** Message code returned when an IBAN is invalid. */
    public static final String IBAN_INVALID_MESSAGE = "error.project.bankAccount.invalid";

    private static final Pattern SHAPE = Pattern.compile("^[A-Z]{2}\\d{2}[A-Z0-9]{11,30}$");
    private static final Pattern NRB = Pattern.compile("^\\d{26}$");

    /** IBAN length per country for common countries. Countries not listed only get the shape check. */
    private static final Map<String, Integer> COUNTRY_LENGTH = Map.ofEntries(
            Map.entry("AT", 20), Map.entry("BE", 16), Map.entry("BG", 22), Map.entry("CH", 21),
            Map.entry("CY", 28), Map.entry("CZ", 24), Map.entry("DE", 22), Map.entry("DK", 18),
            Map.entry("EE", 20), Map.entry("ES", 24), Map.entry("FI", 18), Map.entry("FR", 27),
            Map.entry("GB", 22), Map.entry("GR", 27), Map.entry("HR", 21), Map.entry("HU", 28),
            Map.entry("IE", 22), Map.entry("IT", 27), Map.entry("LT", 20), Map.entry("LU", 20),
            Map.entry("LV", 21), Map.entry("MT", 31), Map.entry("NL", 18), Map.entry("NO", 15),
            Map.entry("PL", 28), Map.entry("PT", 25), Map.entry("RO", 24), Map.entry("SE", 24),
            Map.entry("SI", 19), Map.entry("SK", 24), Map.entry("UA", 29));

    private IbanValidator() {
        // Utility class: no instances.
    }

    /**
     * Normalizes and validates an IBAN.
     *
     * @param raw the submitted value; must not be {@code null}
     * @return the normalized IBAN (upper case, no separators, {@code PL} added to a bare NRB)
     * @throws ForemenApiException 400 {@code error.project.bankAccount.invalid} when invalid or null
     */
    public static String normalize(String raw) {
        String normalized = raw == null ? null : clean(raw);
        if (normalized == null || !isValidNormalized(normalized)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, IBAN_INVALID_MESSAGE);
        }
        return normalized;
    }

    /** Reports whether the value is a valid IBAN (or bare Polish NRB) without throwing. */
    public static boolean isValid(String raw) {
        return raw != null && isValidNormalized(clean(raw));
    }

    private static String clean(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != ' ' && c != '-') {
                sb.append(Character.toUpperCase(c));
            }
        }
        String s = sb.toString();
        return NRB.matcher(s).matches() ? "PL" + s : s;
    }

    private static boolean isValidNormalized(String iban) {
        if (!SHAPE.matcher(iban).matches()) {
            return false;
        }
        Integer expected = COUNTRY_LENGTH.get(iban.substring(0, 2));
        if (expected != null && expected != iban.length()) {
            return false;
        }
        return mod97(iban) == 1;
    }

    /** ISO 7064 mod 97-10 remainder of the rearranged IBAN (letters expand to 10..35). */
    static int mod97(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int rem = 0;
        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            if (c >= '0' && c <= '9') {
                rem = (rem * 10 + (c - '0')) % 97;
            } else {
                rem = (rem * 100 + (c - 'A' + 10)) % 97;
            }
        }
        return rem;
    }
}
