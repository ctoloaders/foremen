package com.foremen.service.bank.account;

import com.foremen.dao.model.HolderMatch;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 12 (FOR-07-04): holder match — equal NIPs ⇒ {@code MATCH}; no holder data (or no company)
 * ⇒ {@code UNKNOWN}; equal names up to case, diacritics and legal-form suffix ⇒ {@code MATCH};
 * present but unrelated holder data ⇒ {@code MISMATCH}. Plus examples.
 *
 * <p>Feature: FOR-07-04-bank-ledger, Property 12: Holder match
 *
 * <p><b>Validates: Requirements 3.7</b>
 */
@Tag("Feature: FOR-07-04-bank-ledger, Property 12: Holder match")
class HolderMatcherPropertyTest {

    private static final List<String> LEGAL_SUFFIXES = List.of(
            "", " Sp. z o.o.", " sp. z o. o.", " SPÓŁKA Z O.O.", " Spółka z ograniczoną odpowiedzialnością",
            " S.A.", " Spółka Akcyjna", " sp.j.", " sp.k.", " s.c.");

    private static final Map<Character, Character> DIACRITICS = Map.of(
            'a', 'ą', 'e', 'ę', 'o', 'ó', 'l', 'ł', 's', 'ś', 'z', 'ż', 'c', 'ć', 'n', 'ń');

    /** 1–3 words of 3–10 letters from {@code alphabet}. */
    private static Arbitrary<String> words(char from, char to) {
        return Arbitraries.strings().withCharRange(from, to).ofMinLength(3).ofMaxLength(10)
                .list().ofMinSize(1).ofMaxSize(3).map(ws -> String.join(" ", ws));
    }

    @Provide
    Arbitrary<String> companyNames() {
        return words('a', 'z');
    }

    /** Names over {@code a..m} — token-disjoint from {@link #highNames()}. */
    @Provide
    Arbitrary<String> lowNames() {
        return words('a', 'm');
    }

    /** Names over {@code n..z} — token-disjoint from {@link #lowNames()}. */
    @Provide
    Arbitrary<String> highNames() {
        return words('n', 'z');
    }

    @Provide
    Arbitrary<String> nips() {
        return Arbitraries.strings().numeric().ofLength(10);
    }

    @Provide
    Arbitrary<List<String>> anyNames() {
        return Arbitraries.oneOf(words('a', 'z'), Arbitraries.of("", " ")).list().ofMaxSize(3);
    }

    @Provide
    Arbitrary<List<String>> anyTaxIds() {
        return Arbitraries.strings().numeric().ofMinLength(0).ofMaxLength(12).list().ofMaxSize(3);
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs();
    }

    /** Re-spells {@code base}: random case, diacritics and a random legal-form suffix. */
    private static String variant(String base, long seed) {
        Random rnd = new Random(seed);
        StringBuilder sb = new StringBuilder();
        for (char ch : base.toCharArray()) {
            char out = ch;
            if (DIACRITICS.containsKey(ch) && rnd.nextBoolean()) {
                out = DIACRITICS.get(ch);
            }
            sb.append(rnd.nextBoolean() ? Character.toUpperCase(out) : out);
        }
        return sb + LEGAL_SUFFIXES.get(rnd.nextInt(LEGAL_SUFFIXES.size()));
    }

    /** Formats a NIP as a provider might: plain, dashed, spaced or with a {@code PL} prefix. */
    private static String formatNip(String nip, long seed) {
        return switch ((int) Math.floorMod(seed, 4L)) {
            case 0 -> nip;
            case 1 -> nip.substring(0, 3) + "-" + nip.substring(3, 6) + "-" + nip.substring(6, 8) + "-" + nip.substring(8);
            case 2 -> "PL" + nip;
            default -> "PL " + nip.substring(0, 5) + " " + nip.substring(5);
        };
    }

    // 12a: equal NIP ⇒ MATCH, whatever the names say.
    @Property(tries = 300)
    @Tag("Feature: FOR-07-04-bank-ledger, Property 12: Holder match")
    void equalNipMatches(@ForAll("nips") String nip, @ForAll("companyNames") String company,
                         @ForAll("anyNames") List<String> names, @ForAll("anyTaxIds") List<String> otherTaxIds,
                         @ForAll("seeds") long seed) {
        List<String> taxIds = new ArrayList<>(otherTaxIds);
        taxIds.add(formatNip(nip, seed));
        Collections.shuffle(taxIds, new Random(seed));

        assertThat(HolderMatcher.match(names, taxIds, null, company, nip)).isEqualTo(HolderMatch.MATCH);
        assertThat(HolderMatcher.match(names, taxIds, "Jan Kowalski", null, formatNip(nip, seed + 1)))
                .isEqualTo(HolderMatch.MATCH);
    }

    // 12b: no holder data at all, or no company profile ⇒ UNKNOWN.
    @Property(tries = 300)
    @Tag("Feature: FOR-07-04-bank-ledger, Property 12: Holder match")
    void noDataIsUnknown(@ForAll("companyNames") String company, @ForAll("nips") String nip,
                         @ForAll("anyNames") List<String> names, @ForAll("anyTaxIds") List<String> taxIds) {
        List<String> blanks = names.stream().map(n -> n.isBlank() ? n : "").toList();
        assertThat(HolderMatcher.match(blanks, List.of("", "--"), " ", company, nip))
                .isEqualTo(HolderMatch.UNKNOWN);
        assertThat(HolderMatcher.match(null, null, null, company, nip)).isEqualTo(HolderMatch.UNKNOWN);
        assertThat(HolderMatcher.match(names, taxIds, company, null, null)).isEqualTo(HolderMatch.UNKNOWN);
        assertThat(HolderMatcher.match(names, taxIds, company, " ", "")).isEqualTo(HolderMatch.UNKNOWN);
    }

    // 12c: the same name up to case, diacritics and legal-form suffix ⇒ MATCH (account or connection level).
    @Property(tries = 300)
    @Tag("Feature: FOR-07-04-bank-ledger, Property 12: Holder match")
    void equivalentNameMatches(@ForAll("companyNames") String base, @ForAll("seeds") long seed) {
        String company = variant(base, seed);
        String holder = variant(base, seed + 1);

        assertThat(HolderMatcher.match(List.of(), List.of(), holder, company, null)).isEqualTo(HolderMatch.MATCH);
        assertThat(HolderMatcher.match(List.of("Jan Kowalski", holder), List.of("1234567890"), null, company, null))
                .isEqualTo(HolderMatch.MATCH);
    }

    // 12d: holder data present, unrelated names and different tax ids ⇒ MISMATCH; result is total.
    @Property(tries = 300)
    @Tag("Feature: FOR-07-04-bank-ledger, Property 12: Holder match")
    void unrelatedHolderMismatches(@ForAll("lowNames") String company, @ForAll("highNames") String holder) {
        String nip = "1111111111";
        String otherNip = "2222222222";

        assertThat(HolderMatcher.match(List.of(holder), List.of(otherNip), holder, company, nip))
                .isEqualTo(HolderMatch.MISMATCH);
        assertThat(HolderMatcher.match(null, null, holder, company, nip)).isEqualTo(HolderMatch.MISMATCH);
        assertThat(HolderMatcher.match(null, List.of(otherNip), null, company, nip)).isEqualTo(HolderMatch.MISMATCH);
    }

    // 12e: totality — always one of the three values, never throws.
    @Property(tries = 300)
    @Tag("Feature: FOR-07-04-bank-ledger, Property 12: Holder match")
    void isTotal(@ForAll("anyNames") List<String> names, @ForAll("anyTaxIds") List<String> taxIds,
                 @ForAll("companyNames") String company, @ForAll("nips") String nip, @ForAll boolean withCompany) {
        HolderMatch result = HolderMatcher.match(names, taxIds, names.isEmpty() ? null : names.get(0),
                withCompany ? company : null, withCompany ? nip : null);
        assertThat(result).isNotNull();
    }

    // ------------------------------------------------------------------------------------------
    // Examples (R3.7).
    // ------------------------------------------------------------------------------------------
    @Property(tries = 1)
    void examples() {
        String company = "Foremen Sp. z o.o.";
        String nip = "525-000-00-00";

        // Legal form spelled differently, upper case, diacritics.
        assertThat(HolderMatcher.match(List.of(), List.of(), "FOREMEN SPÓŁKA Z O.O.", company, nip))
                .isEqualTo(HolderMatch.MATCH);
        assertThat(HolderMatcher.match(List.of("Foremen Spółka z ograniczoną odpowiedzialnością"), List.of(),
                null, company, nip)).isEqualTo(HolderMatch.MATCH);
        // NIP with PL prefix.
        assertThat(HolderMatcher.match(List.of("Jan Kowalski"), List.of("PL5250000000"), null, company, nip))
                .isEqualTo(HolderMatch.MATCH);
        // Private person's account.
        assertThat(HolderMatcher.match(List.of(), List.of(), "Jan Kowalski", company, nip))
                .isEqualTo(HolderMatch.MISMATCH);
        assertThat(HolderMatcher.match(List.of("Jan Kowalski"), List.of("9999999999"), "Jan Kowalski", company, nip))
                .isEqualTo(HolderMatch.MISMATCH);
        // Partial name overlap below 0.8.
        assertThat(HolderMatcher.match(List.of(), List.of(), "Foremen Budownictwo Sp. z o.o.", company, nip))
                .isEqualTo(HolderMatch.MISMATCH);
        // Empty data.
        assertThat(HolderMatcher.match(List.of(), List.of(), null, company, nip)).isEqualTo(HolderMatch.UNKNOWN);
        assertThat(HolderMatcher.match(null, null, "", company, nip)).isEqualTo(HolderMatch.UNKNOWN);
        // No company profile.
        assertThat(HolderMatcher.match(List.of(), List.of("5250000000"), "Foremen Sp. z o.o.", null, null))
                .isEqualTo(HolderMatch.UNKNOWN);
        // Custom threshold.
        assertThat(HolderMatcher.match(List.of(), List.of(), "Foremen Budownictwo", company, null, 0.5))
                .isEqualTo(HolderMatch.MATCH);
    }
}
