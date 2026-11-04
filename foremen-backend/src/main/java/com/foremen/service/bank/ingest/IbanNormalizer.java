package com.foremen.service.bank.ingest;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pure helper that normalizes and validates counterparty IBANs (FOR-07-04, Requirement 6.5).
 *
 * <p>Normalization strips every whitespace character and dash and upper-cases the value. A bare
 * Polish 26-digit NRB number is prefixed with {@code PL}. Validation applies the ISO 13616 mod-97
 * check (the rearranged numeric form modulo 97 must equal 1).
 *
 * <p>Stateless and side-effect free; {@link #normalize(String)} is idempotent (design Property 10).
 */
public final class IbanNormalizer {

    /** Polish country code used to prefix a bare NRB number. */
    public static final String PL = "PL";

    /** Length of a Polish NRB account number (2 check digits + 24 digits). */
    public static final int NRB_LENGTH = 26;

    private static final Pattern STRIP = Pattern.compile("[\\s\\-\\u00A0]+");
    private static final Pattern NRB = Pattern.compile("\\d{26}");
    private static final Pattern IBAN_SHAPE = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}");
    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    private IbanNormalizer() {
        // Utility class: no instances.
    }

    /**
     * Cleans a raw value: strips spaces / dashes, upper-cases, prefixes a 26-digit NRB with
     * {@code PL}. No validation is performed.
     *
     * @param raw provider value, may be {@code null}
     * @return the cleaned value, or {@code null} when the input is {@code null} or blank
     */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = STRIP.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            return null;
        }
        if (NRB.matcher(cleaned).matches()) {
            return PL + cleaned;
        }
        return cleaned;
    }

    /**
     * Returns {@code true} when the value is a well-formed IBAN (already in cleaned form) that passes
     * the mod-97 check.
     */
    public static boolean isValid(String iban) {
        if (iban == null || !IBAN_SHAPE.matcher(iban).matches()) {
            return false;
        }
        if (iban.startsWith(PL) && iban.length() != PL.length() + NRB_LENGTH) {
            return false;
        }
        return mod97(iban.substring(4) + iban.substring(0, 4)) == 1;
    }

    /**
     * Cleans and validates a raw IBAN.
     *
     * @return the normalized IBAN when valid, otherwise empty
     */
    public static Optional<String> normalize(String raw) {
        String cleaned = clean(raw);
        return isValid(cleaned) ? Optional.of(cleaned) : Optional.empty();
    }

    /**
     * Computes the two ISO 13616 check digits for a country code and BBAN.
     *
     * @param countryCode two upper-case letters
     * @param bban alphanumeric upper-case basic bank account number
     * @return the two check digits, zero-padded
     */
    public static String checkDigits(String countryCode, String bban) {
        int remainder = mod97(bban + countryCode + "00");
        return String.format(Locale.ROOT, "%02d", 98 - remainder);
    }

    /** Converts letters to numbers (A=10 … Z=35) and returns the value modulo 97. */
    private static int mod97(String rearranged) {
        StringBuilder digits = new StringBuilder(rearranged.length() * 2);
        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else {
                digits.append(c - 'A' + 10);
            }
        }
        return new BigInteger(digits.toString()).mod(NINETY_SEVEN).intValue();
    }
}
