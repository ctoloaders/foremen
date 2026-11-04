package com.foremen.service.bank.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.service.bank.detect.NameSimilarity;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IbanNormalizer}, {@link CounterpartyNameNormalizer},
 * {@link CounterpartyExtractor} and {@link NameSimilarity} (FOR-07-04 task 2.1).
 *
 * <p><b>Validates: Requirements 6.5, 14.5, 14.6</b>
 */
class CounterpartyNormalizationTest {

    private static final String PL_IBAN = "PL61109010140000071219812874";

    @Test
    void ibanIsStrippedUppercasedAndValidated() {
        assertThat(IbanNormalizer.normalize(" pl61 1090-1014 0000 0712 1981 2874 ")).contains(PL_IBAN);
        assertThat(IbanNormalizer.normalize("DE89 3704 0044 0532 0130 00")).contains("DE89370400440532013000");
    }

    @Test
    void bareNrbIsPrefixedWithPl() {
        assertThat(IbanNormalizer.clean("61 1090 1014 0000 0712 1981 2874")).isEqualTo(PL_IBAN);
        assertThat(IbanNormalizer.normalize("61109010140000071219812874")).contains(PL_IBAN);
    }

    @Test
    void invalidIbansAreRejected() {
        assertThat(IbanNormalizer.normalize("PL62109010140000071219812874")).isEmpty(); // bad check
        assertThat(IbanNormalizer.normalize("PL6110901014")).isEmpty(); // too short
        assertThat(IbanNormalizer.normalize("not an iban")).isEmpty();
        assertThat(IbanNormalizer.normalize("   ")).isEmpty();
        assertThat(IbanNormalizer.normalize(null)).isEmpty();
        assertThat(IbanNormalizer.clean("  ")).isNull();
    }

    @Test
    void checkDigitsMatchKnownIban() {
        assertThat(IbanNormalizer.checkDigits("PL", "109010140000071219812874")).isEqualTo("61");
    }

    @Test
    void namesAreNormalizedForDiacriticsCaseAndLegalSuffixes() {
        assertThat(CounterpartyNameNormalizer.normalize("  Łódzkie   Żurawie Sp. z o.o. "))
                .isEqualTo("lodzkie zurawie");
        assertThat(CounterpartyNameNormalizer.normalize("BUD-MAR S.A.")).isEqualTo("bud mar");
        assertThat(CounterpartyNameNormalizer.normalize("Kowalski i Syn sp.j.")).isEqualTo("kowalski i syn");
        assertThat(CounterpartyNameNormalizer.normalize("Gęś Spółka z ograniczoną odpowiedzialnością"))
                .isEqualTo("ges");
        // a suffix-only name is not emptied
        assertThat(CounterpartyNameNormalizer.normalize("S.A.")).isEqualTo("s a");
        assertThat(CounterpartyNameNormalizer.normalize(null)).isEmpty();
        assertThat(CounterpartyNameNormalizer.normalize(" .,- ")).isEmpty();
    }

    @Test
    void extractorPrefersIbanKey() {
        CounterpartyExtractor.Counterparty c =
                CounterpartyExtractor.extract("  Jan   Kowalski ", "61 1090 1014 0000 0712 1981 2874");
        assertThat(c.name()).isEqualTo("Jan Kowalski");
        assertThat(c.iban()).isEqualTo(PL_IBAN);
        assertThat(c.ibanInvalid()).isFalse();
        assertThat(c.key()).isEqualTo("IBAN:" + PL_IBAN);
    }

    @Test
    void extractorFallsBackToNameKeyOnInvalidOrMissingIban() {
        CounterpartyExtractor.Counterparty invalid =
                CounterpartyExtractor.extract("Żurawie Sp. z o.o.", "PL00 1234");
        assertThat(invalid.iban()).isNull();
        assertThat(invalid.ibanInvalid()).isTrue();
        assertThat(invalid.key()).isEqualTo("NAME:zurawie");

        CounterpartyExtractor.Counterparty missing = CounterpartyExtractor.extract("Jan Kowalski", null);
        assertThat(missing.ibanInvalid()).isFalse();
        assertThat(missing.key()).isEqualTo("NAME:jan kowalski");

        CounterpartyExtractor.Counterparty none = CounterpartyExtractor.extract("  ", "");
        assertThat(none.name()).isNull();
        assertThat(none.key()).isNull();
        assertThat(none.ibanInvalid()).isFalse();
    }

    @Test
    void nameSimilarityIsTokenJaccard() {
        assertThat(NameSimilarity.similarity("Jan Kowalski", "KOWALSKI Jan")).isEqualTo(1.0);
        assertThat(NameSimilarity.similarity("Łukasz Żak", "Lukasz Zak")).isEqualTo(1.0);
        assertThat(NameSimilarity.similarity("Bud-Mar Sp. z o.o.", "BUD MAR")).isEqualTo(1.0);
        assertThat(NameSimilarity.similarity("Jan Kowalski", "Jan Nowak")).isEqualTo(1.0 / 3);
        assertThat(NameSimilarity.similarity("Jan Kowalski", "Piotr Nowak")).isZero();
        assertThat(NameSimilarity.similarity(null, "")).isZero();
        assertThat(NameSimilarity.similar("Jan Maria Kowalski", "Jan Kowalski")).isTrue(); // 2/3
        assertThat(NameSimilarity.similar("Jan Kowalski", "Jan Nowak")).isFalse();
    }
}
