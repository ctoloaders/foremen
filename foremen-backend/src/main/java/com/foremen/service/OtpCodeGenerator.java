package com.foremen.service;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * Generates one-time-password (OTP) codes for client authentication (Requirement 4.5).
 *
 * <p>Each code consists of exactly 6 decimal digits with leading zeros preserved (for
 * example {@code 007413}). The value is drawn from a cryptographically-secure random
 * source, so the output always matches the pattern {@code ^[0-9]{6}$}.
 */
@Component
public class OtpCodeGenerator {

    /** Number of decimal digits in a generated code. */
    private static final int DIGITS = 6;

    /** Upper bound (exclusive) for the random value: 000000..999999. */
    private static final int BOUND = 1_000_000;

    /** Format string that zero-pads the value to {@link #DIGITS} width. */
    private static final String FORMAT = "%0" + DIGITS + "d";

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Returns a fresh OTP code of exactly 6 decimal digits, leading zeros preserved
     * (Requirement 4.5).
     *
     * <p>{@link SecureRandom#nextInt(int)} draws uniformly in {@code [0, 999999]} and
     * {@link String#format(String, Object...)} with {@code %06d} guarantees a width-6
     * string with leading zeros.
     */
    public String generate() {
        int value = secureRandom.nextInt(BOUND);
        return String.format(FORMAT, value);
    }
}
