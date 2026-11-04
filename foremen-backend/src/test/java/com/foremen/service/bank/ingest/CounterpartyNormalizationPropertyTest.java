package com.foremen.service.bank.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.service.bank.detect.NameSimilarity;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for the FOR-07-04 counterparty normalization helpers (task 2.1).
 *
 * <p>Feature: FOR-07-04-bank-ledger, Property 10: IBAN normalization — idempotent; a valid PL NRB
 * normalizes to {@code PL} + 26 digits passing mod-97. Plus name normalization idempotence and
 * {@link NameSimilarity} symmetry / identity.
 *
 * <p><b>Validates: Requirements 6.5, 14.5, 14.6</b>
 */
@Tag("Feature: FOR-07-04-bank-ledger, Property 10: IBAN normalization")
class CounterpartyNormalizationPropertyTest {

    /** Valid NRB: 2 computed check digits + 24 random digits. */
    @Provide
    Arbitrary<String> validNrb() {
        return Arbitraries.strings().numeric().ofLength(24)
                .map(bban -> IbanNormalizer.checkDigits("PL", bban) + bban);
    }

    /** Raw IBAN-ish inputs: digits, letters, spaces, dashes, mixed case. */
    @Provide
    Arbitrary<String> rawIbanish() {
        Arbitrary<String> noise = Arbitraries.strings()
                .withChars("0123456789 -plPLdeDEAZaz")
                .ofMaxLength(40);
        Arbitrary<String> formattedNrb = validNrb().map(n -> n.replaceAll("(.{4})", "$1 "));
        return Arbitraries.oneOf(noise, formattedNrb, validNrb(), validNrb().map(n -> "pl" + n));
    }

    /** Names mixing Polish letters, punctuation, legal suffixes and whitespace. */
    @Provide
    Arbitrary<String> names() {
        Arbitrary<String> word = Arbitraries.strings()
                .withChars("abcęłóśźżąćńABCĘŁÓŚŹŻ.-,")
                .ofMinLength(1).ofMaxLength(8);
        Arbitrary<String> suffix = Arbitraries.of("", " sp. z o.o.", " S.A.", " sp.j.", " s.c.", " Sp. k.");
        return word.list().ofMinSize(0).ofMaxSize(4)
                .flatMap(words -> suffix.map(s -> String.join("  ", words) + s));
    }

    @Property(tries = 200)
    void validNrbRoundTrips(@ForAll("validNrb") String nrb) {
        String spaced = nrb.replaceAll("(.{4})", "$1 ");
        Optional<String> normalized = IbanNormalizer.normalize(spaced);
        assertThat(normalized).contains("PL" + nrb);
        assertThat(IbanNormalizer.isValid(normalized.get())).isTrue();
        assertThat(normalized.get()).matches("PL\\d{26}");
        assertThat(IbanNormalizer.normalize(normalized.get())).isEqualTo(normalized);
    }

    @Property(tries = 300)
    void ibanNormalizationIsIdempotent(@ForAll("rawIbanish") String raw) {
        String once = IbanNormalizer.clean(raw);
        assertThat(IbanNormalizer.clean(once)).isEqualTo(once);
        Optional<String> normalized = IbanNormalizer.normalize(raw);
        normalized.ifPresent(n -> assertThat(IbanNormalizer.normalize(n)).contains(n));
    }

    @Property(tries = 300)
    void nameNormalizationIsIdempotent(@ForAll("names") String name) {
        String once = CounterpartyNameNormalizer.normalize(name);
        assertThat(CounterpartyNameNormalizer.normalize(once)).isEqualTo(once);
        String key = CounterpartyExtractor.extract(name, null).key();
        if (key != null) {
            assertThat(CounterpartyExtractor.extract(key.substring("NAME:".length()), null).key())
                    .isEqualTo(key);
        }
    }

    @Property(tries = 300)
    void similarityIsSymmetricAndBounded(@ForAll("names") String a, @ForAll("names") String b) {
        double ab = NameSimilarity.similarity(a, b);
        assertThat(ab).isEqualTo(NameSimilarity.similarity(b, a));
        assertThat(ab).isBetween(0.0, 1.0);
    }

    @Property(tries = 300)
    void similarityIsOneOnEqualNormalizedNames(@ForAll("names") String a) {
        String normalized = CounterpartyNameNormalizer.normalize(a);
        if (!normalized.isEmpty()) {
            assertThat(NameSimilarity.similarity(a, normalized)).isEqualTo(1.0);
            assertThat(NameSimilarity.similarity(a, a.toUpperCase())).isEqualTo(1.0);
        }
    }
}
