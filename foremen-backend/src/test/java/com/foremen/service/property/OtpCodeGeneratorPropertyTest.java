package com.foremen.service.property;

// Feature: FOR-03-05-otp-client-auth, Property 1: OTP code shape

import com.foremen.service.OtpCodeGenerator;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 1: OTP code shape.
 *
 * <p>For all invocations of the code generator, the produced OTP_Code SHALL be a string of
 * exactly 6 characters in which every character is a decimal digit ({@code 0}-{@code 9}), with
 * leading zeros preserved (matching {@code ^[0-9]{6}$}).
 *
 * <p><b>Validates: Requirements 4.5</b>
 */
class OtpCodeGeneratorPropertyTest {

    private static final String SIX_DIGITS = "^[0-9]{6}$";

    private final OtpCodeGenerator generator = new OtpCodeGenerator();

    /**
     * Every generated code matches {@code ^[0-9]{6}$}: exactly six characters, all decimal digits,
     * leading zeros preserved. {@code generate()} draws from {@link java.security.SecureRandom}
     * internally, so the {@code @ForAll} parameter merely drives repeated independent invocations.
     */
    @Property(tries = 100)
    void generatedCodeIsAlwaysSixDigits(@ForAll int ignoredSeed) {
        String code = generator.generate();

        assertThat(code)
                .hasSize(6)
                .matches(SIX_DIGITS);
    }

    /** Leading zeros are preserved: the value {@code 0} must render as {@code "000000"}, not {@code "0"}. */
    @Property(tries = 100)
    void everyCharacterIsADecimalDigit(@ForAll int ignoredSeed) {
        String code = generator.generate();

        assertThat(code).hasSize(6);
        for (int i = 0; i < code.length(); i++) {
            assertThat(Character.isDigit(code.charAt(i)))
                    .as("character at index %d of %s is a decimal digit", i, code)
                    .isTrue();
        }
    }
}
