package com.foremen.service;

import com.foremen.exception.ForemenApiException;
import org.springframework.http.HttpStatus;

/**
 * Pure helper for normalizing and checksum-validating a Polish tax identifier (NIP) supplied for a
 * COMPANY worker record (Requirement 13 criterion 5, FOR-05-09-team-selection).
 *
 * <p>Normalization removes every space and hyphen from the input. The result is accepted only when
 * it is exactly ten decimal digits whose weighted checksum is valid: the sum of the first nine
 * digits multiplied by the weights {@code 6, 5, 7, 2, 3, 4, 5, 6, 7}, taken modulo 11, must not be
 * 10 and must equal the tenth digit. A valid NIP is returned in its normalized ten-digit form; any
 * other input is rejected with HTTP 400 and message code {@code error.worker.nip.invalid}.
 *
 * <p>This class is stateless and side-effect free; the single entry point {@link #normalize(String)}
 * is a pure function suitable for direct property-based testing.
 */
public final class NipValidator {

    /** Message code returned when a NIP fails normalization or the checksum rule. */
    public static final String NIP_INVALID_MESSAGE = "error.worker.nip.invalid";

    /** Number of decimal digits a normalized NIP must contain. */
    private static final int NIP_LENGTH = 10;

    /** Checksum weights applied to the first nine digits (Requirement 13 criterion 5). */
    private static final int[] WEIGHTS = {6, 5, 7, 2, 3, 4, 5, 6, 7};

    /** Modulus of the weighted checksum. */
    private static final int MODULUS = 11;

    /** A weighted-sum remainder of this value makes the NIP invalid (never equals a digit). */
    private static final int INVALID_REMAINDER = 10;

    private NipValidator() {
        // Utility class: no instances.
    }

    /**
     * Normalizes and validates a supplied NIP.
     *
     * <p>Removes all spaces and hyphens, then accepts the value only if it is exactly ten decimal
     * digits whose weighted checksum (weights {@code 6, 5, 7, 2, 3, 4, 5, 6, 7} modulo 11, not 10,
     * equal to the tenth digit) is valid.
     *
     * @param rawNip the submitted NIP (may contain spaces and hyphens); must not be {@code null}
     * @return the normalized ten-digit NIP when it is valid
     * @throws ForemenApiException 400 {@code error.worker.nip.invalid} when the normalized value is
     *         not exactly ten decimal digits or its checksum is invalid, or when {@code rawNip} is
     *         {@code null}
     */
    public static String normalize(String rawNip) {
        if (rawNip == null) {
            throw invalid();
        }
        String normalized = stripSeparators(rawNip);
        if (!isValid(normalized)) {
            throw invalid();
        }
        return normalized;
    }

    /**
     * Reports whether a submitted NIP is valid without throwing.
     *
     * @param rawNip the submitted NIP (may contain spaces and hyphens); {@code null} is invalid
     * @return {@code true} iff, after removing spaces and hyphens, the value is exactly ten decimal
     *         digits with a valid checksum
     */
    public static boolean isValidNip(String rawNip) {
        return rawNip != null && isValid(stripSeparators(rawNip));
    }

    private static String stripSeparators(String rawNip) {
        StringBuilder sb = new StringBuilder(rawNip.length());
        for (int i = 0; i < rawNip.length(); i++) {
            char c = rawNip.charAt(i);
            if (c != ' ' && c != '-') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isValid(String candidate) {
        if (candidate.length() != NIP_LENGTH) {
            return false;
        }
        for (int i = 0; i < NIP_LENGTH; i++) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += WEIGHTS[i] * (candidate.charAt(i) - '0');
        }
        int remainder = sum % MODULUS;
        if (remainder == INVALID_REMAINDER) {
            return false;
        }
        int checkDigit = candidate.charAt(NIP_LENGTH - 1) - '0';
        return remainder == checkDigit;
    }

    private static ForemenApiException invalid() {
        return new ForemenApiException(HttpStatus.BAD_REQUEST, NIP_INVALID_MESSAGE);
    }
}
