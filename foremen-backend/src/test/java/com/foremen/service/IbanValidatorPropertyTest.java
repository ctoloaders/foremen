package com.foremen.service;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property: IBANs built with correct mod-97 check digits are accepted in any spacing and case, a bare
 * Polish NRB is accepted with the PL prefix, and changing one digit of the account part is rejected.
 *
 * <p><b>Validates: Requirements 4.1</b>
 */
class IbanValidatorPropertyTest {

    private static String withCheckDigits(String country, String bban) {
        StringBuilder sb = new StringBuilder();
        for (char c : (bban + country + "00").toCharArray()) {
            sb.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        int check = 98 - new BigInteger(sb.toString()).mod(BigInteger.valueOf(97)).intValue();
        return country + String.format("%02d", check) + bban;
    }

    @Provide
    Arbitrary<String> plIbans() {
        return Arbitraries.strings().numeric().ofLength(24).map(b -> withCheckDigits("PL", b));
    }

    @Provide
    Arbitrary<String> deIbans() {
        return Arbitraries.strings().numeric().ofLength(18).map(b -> withCheckDigits("DE", b));
    }

    @Property
    void generatedIbansAreValidInAnySpacingAndCase(@ForAll("deIbans") String iban, @ForAll boolean lower) {
        String spaced = iban.replaceAll("(.{4})", "$1 ");
        String raw = lower ? spaced.toLowerCase() : spaced;
        assertThat(IbanValidator.isValid(raw)).isTrue();
        assertThat(IbanValidator.normalize(raw)).isEqualTo(iban);
    }

    @Property
    void polishNrbWithoutPrefixIsAccepted(@ForAll("plIbans") String iban) {
        String nrb = iban.substring(2);
        assertThat(IbanValidator.normalize(nrb)).isEqualTo(iban);
    }

    @Property
    void singleDigitChangeIsRejected(@ForAll("plIbans") String iban,
                                     @ForAll("positions") int pos, @ForAll("deltas") int delta) {
        char[] chars = iban.toCharArray();
        chars[pos] = (char) ('0' + ((chars[pos] - '0') + delta) % 10);
        assertThat(IbanValidator.isValid(new String(chars))).isFalse();
    }

    @Provide
    Arbitrary<Integer> positions() {
        return Arbitraries.integers().between(2, 27);
    }

    @Provide
    Arbitrary<Integer> deltas() {
        return Arbitraries.integers().between(1, 9);
    }
}
