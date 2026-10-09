package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foremen.exception.ForemenApiException;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.springframework.http.HttpStatus;

/**
 * Property-based tests for {@link NipValidator} — the pure helper that normalizes and
 * checksum-validates a Polish tax identifier (NIP) supplied for a COMPANY worker record
 * (FOR-05-09-team-selection, design §Property 12).
 *
 * <p>The validator removes every space and hyphen, then accepts the value if and only if the result
 * is exactly ten decimal digits whose weighted checksum is valid: the sum of the first nine digits
 * multiplied by the weights {@code 6, 5, 7, 2, 3, 4, 5, 6, 7}, taken modulo 11, must not be 10 and
 * must equal the tenth digit. A valid NIP is returned in its normalized ten-digit form; any other
 * input is rejected with HTTP 400 and message code {@code error.worker.nip.invalid}. The validator is
 * exercised directly as a pure function — no persistence — so Property 12 is cheap to run over 100+
 * iterations.
 *
 * <p>Feature: FOR-05-09-team-selection, Property 12: NIP checksum validation
 *
 * <p><b>Validates: Requirements 13.5</b>
 */
@Tag("Feature: FOR-05-09-team-selection, Property 12: NIP checksum validation")
class NipValidatorPropertyTest {

    /** Checksum weights applied to the first nine digits (Requirement 13 criterion 5). */
    private static final int[] WEIGHTS = {6, 5, 7, 2, 3, 4, 5, 6, 7};

    private static final int MODULUS = 11;
    private static final int INVALID_REMAINDER = 10;
    private static final String NIP_INVALID = "error.worker.nip.invalid";

    // ------------------------------------------------------------------------------------------
    // Property 12a: every ten-digit string with a correct check digit is accepted and returned in
    // its normalized ten-digit form, regardless of how many spaces/hyphens are interspersed.
    // Validates: Requirement 13.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 12: NIP checksum validation")
    void validNipWithSeparatorsIsAcceptedAndNormalized(@ForAll("validNipWithSeparators") NipCase c) {
        String result = NipValidator.normalize(c.raw());

        assertThat(result)
                .as("normalize(%s) should strip separators to the canonical ten digits", c.raw())
                .isEqualTo(c.canonical());
        assertThat(NipValidator.isValidNip(c.raw()))
                .as("isValidNip(%s) agrees with normalize for a valid NIP", c.raw())
                .isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // Property 12b: the accept/reject decision is exactly the checksum oracle. For any string of
    // digits, spaces and hyphens, normalize() succeeds iff the stripped value is ten digits with a
    // valid checksum, and otherwise throws 400 error.worker.nip.invalid.
    // Validates: Requirement 13.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 300)
    @Tag("Feature: FOR-05-09-team-selection, Property 12: NIP checksum validation")
    void acceptanceMatchesTheChecksumOracle(@ForAll("digitsAndSeparators") String raw) {
        String stripped = raw.replace(" ", "").replace("-", "");
        boolean expectedValid = isValidByOracle(stripped);

        if (expectedValid) {
            assertThat(NipValidator.normalize(raw))
                    .as("normalize(%s) returns the stripped ten-digit value", raw)
                    .isEqualTo(stripped);
            assertThat(NipValidator.isValidNip(raw)).isTrue();
        } else {
            assertThatThrownBy(() -> NipValidator.normalize(raw))
                    .as("normalize(%s) rejects an invalid NIP", raw)
                    .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(ex.getMessageCode()).isEqualTo(NIP_INVALID);
                    });
            assertThat(NipValidator.isValidNip(raw)).isFalse();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 12c: a ten-digit string whose weighted sum is otherwise correct but has a wrong tenth
    // digit (any digit != the computed remainder, and remainder != 10) is always rejected.
    // Validates: Requirement 13.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 12: NIP checksum validation")
    void wrongCheckDigitIsRejected(@ForAll("validNipWithSeparators") NipCase c, @ForAll int offset) {
        String canonical = c.canonical();
        int correct = canonical.charAt(9) - '0';
        int wrong = Math.floorMod(correct + 1 + Math.floorMod(offset, 9), 10);
        if (wrong == correct) {
            wrong = (correct + 1) % 10;
        }
        String tampered = canonical.substring(0, 9) + (char) ('0' + wrong);

        assertThat(NipValidator.isValidNip(tampered))
                .as("%s has a wrong check digit (correct=%d) and must be rejected", tampered, correct)
                .isFalse();
        assertThatThrownBy(() -> NipValidator.normalize(tampered))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        ex -> assertThat(ex.getMessageCode()).isEqualTo(NIP_INVALID));
    }

    // ------------------------------------------------------------------------------------------
    // Property 12d: a stripped value whose length is not exactly ten is always rejected, whatever its
    // digits are.
    // Validates: Requirement 13.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 12: NIP checksum validation")
    void wrongLengthIsRejected(@ForAll("digitsOfWrongLength") String digits) {
        assertThat(NipValidator.isValidNip(digits))
                .as("%s is not ten digits and must be rejected", digits)
                .isFalse();
        assertThatThrownBy(() -> NipValidator.normalize(digits))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        ex -> assertThat(ex.getMessageCode()).isEqualTo(NIP_INVALID));
    }

    // ------------------------------------------------------------------------------------------
    // Property 12e: a ten-character string that, after stripping, contains any non-digit character is
    // always rejected.
    // Validates: Requirement 13.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 12: NIP checksum validation")
    void nonDigitCharactersAreRejected(@ForAll("tenCharsWithNonDigit") String value) {
        assertThat(NipValidator.isValidNip(value))
                .as("%s contains a non-digit and must be rejected", value)
                .isFalse();
        assertThatThrownBy(() -> NipValidator.normalize(value))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        ex -> assertThat(ex.getMessageCode()).isEqualTo(NIP_INVALID));
    }

    // ------------------------------------------------------------------------------------------
    // Example: the remainder==10 case is always rejected even though the tenth digit might otherwise
    // "match" an impossible value. We construct the first nine digits whose weighted sum % 11 == 10
    // and assert rejection for every possible tenth digit 0..9.
    // Validates: Requirement 13.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-09-team-selection, Property 12: NIP checksum validation")
    void remainderTenCaseIsAlwaysRejected(@ForAll("nineDigitsWithRemainderTen") String firstNine,
                                          @ForAll("digitChar") char tenth) {
        String candidate = firstNine + tenth;

        assertThat(weightedRemainder(firstNine))
                .as("generator precondition: weighted remainder of %s is 10", firstNine)
                .isEqualTo(INVALID_REMAINDER);
        assertThat(NipValidator.isValidNip(candidate))
                .as("%s has remainder 10 and must be rejected for every tenth digit", candidate)
                .isFalse();
    }

    @Property(tries = 10)
    @Tag("Feature: FOR-05-09-team-selection, Property 12: NIP checksum validation")
    void nullIsRejected() {
        assertThat(NipValidator.isValidNip(null)).isFalse();
        assertThatThrownBy(() -> NipValidator.normalize(null))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        ex -> assertThat(ex.getMessageCode()).isEqualTo(NIP_INVALID));
    }

    // ------------------------------------------------------------------------------------------
    // Oracle + helpers
    // ------------------------------------------------------------------------------------------

    private static boolean isValidByOracle(String stripped) {
        if (stripped.length() != 10) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            char ch = stripped.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        int remainder = weightedRemainder(stripped.substring(0, 9));
        if (remainder == INVALID_REMAINDER) {
            return false;
        }
        return remainder == (stripped.charAt(9) - '0');
    }

    private static int weightedRemainder(String nineDigits) {
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += WEIGHTS[i] * (nineDigits.charAt(i) - '0');
        }
        return sum % MODULUS;
    }

    /** A valid-NIP case: the canonical ten-digit value and a raw form with interspersed separators. */
    private record NipCase(String canonical, String raw) {
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * A valid ten-digit NIP whose check digit is computed from random first-nine digits (rejecting the
     * remainder==10 case), together with a raw form in which spaces and hyphens are randomly
     * interspersed so separator stripping is exercised.
     */
    @Provide
    Arbitrary<NipCase> validNipWithSeparators() {
        return nineDigitsWithValidChecksum().flatMap(firstNine -> {
            int remainder = weightedRemainder(firstNine);
            String canonical = firstNine + (char) ('0' + remainder);
            return separatorInsertions(canonical).map(raw -> new NipCase(canonical, raw));
        });
    }

    /** Nine digits whose weighted remainder is in {@code [0, 9]} (i.e. a valid check digit exists). */
    @Provide
    Arbitrary<String> nineDigitsWithValidChecksum() {
        return nineDigitStrings().filter(s -> weightedRemainder(s) != INVALID_REMAINDER);
    }

    /** Nine digits whose weighted remainder is exactly 10 — no valid NIP can be built from them. */
    @Provide
    Arbitrary<String> nineDigitsWithRemainderTen() {
        return nineDigitStrings().filter(s -> weightedRemainder(s) == INVALID_REMAINDER);
    }

    private Arbitrary<String> nineDigitStrings() {
        return Arbitraries.strings().withCharRange('0', '9').ofLength(9);
    }

    /** Arbitrary mixtures of digits, spaces and hyphens — the full input alphabet of the stripper. */
    @Provide
    Arbitrary<String> digitsAndSeparators() {
        return Arbitraries.strings()
                .withChars('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ' ', '-')
                .ofMinLength(0)
                .ofMaxLength(14);
    }

    /** Digit strings of a length other than ten (0..9 and 11..15), all invalid by length. */
    @Provide
    Arbitrary<String> digitsOfWrongLength() {
        Arbitrary<Integer> length = Arbitraries.integers().between(0, 15).filter(n -> n != 10);
        return length.flatMap(n -> Arbitraries.strings().withCharRange('0', '9').ofLength(n));
    }

    /** Ten-character strings containing at least one non-digit, non-separator character. */
    @Provide
    Arbitrary<String> tenCharsWithNonDigit() {
        Arbitrary<String> base = Arbitraries.strings().withCharRange('0', '9').ofLength(9);
        Arbitrary<Character> nonDigit = Arbitraries.chars()
                .range('a', 'z')
                .with('/', '.', '*', '+', 'X');
        return Combinators.combine(base, nonDigit, Arbitraries.integers().between(0, 9))
                .as((digits, bad, pos) -> {
                    StringBuilder sb = new StringBuilder(digits);
                    sb.insert((int) pos, (char) bad);
                    return sb.toString();
                });
    }

    @Provide
    Arbitrary<Character> digitChar() {
        return Arbitraries.chars().range('0', '9');
    }

    /** Insert zero or more spaces/hyphens between/around the characters of a value, keeping order. */
    private Arbitrary<String> separatorInsertions(String value) {
        Arbitrary<List<String>> gaps = Arbitraries.of("", "", "", " ", "-", " -", "- ", "  ")
                .list()
                .ofSize(value.length() + 1);
        return gaps.map(fillers -> {
            StringBuilder sb = new StringBuilder();
            List<String> f = new ArrayList<>(fillers);
            sb.append(f.get(0));
            for (int i = 0; i < value.length(); i++) {
                sb.append(value.charAt(i));
                sb.append(f.get(i + 1));
            }
            return sb.toString();
        });
    }
}
