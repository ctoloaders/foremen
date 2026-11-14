package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * FOR-11-00 task 11.6 - properties of {@link SellerCodeGenerator} (Requirement 20.4).
 *
 * <p>Validates Requirements 20.4.
 */
class SellerCodeGeneratorPropertyTest {

    private static final String CODE = "^[a-z0-9_]{1,100}$";

    @Provide
    Arbitrary<String> names() {
        Arbitrary<String> ascii = Arbitraries.strings().ascii().ofMaxLength(150);
        Arbitrary<String> mixed = Arbitraries.strings()
                .withChars("ąćęłńóśźżĄĆĘŁŃÓŚŹŻабвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВ -_./&0123456789abcXYZ")
                .ofMaxLength(150);
        Arbitrary<String> unicode = Arbitraries.strings().ofMaxLength(120);
        return Arbitraries.oneOf(ascii, mixed, unicode);
    }

    @Provide
    Arbitrary<Set<String>> takenSets() {
        return Arbitraries.strings().withCharRange('a', 'c').withChars('_', '2', '3').ofMaxLength(6)
                .set().ofMaxSize(30);
    }

    @Property
    void resultAlwaysMatchesCodePattern(@ForAll("names") String name, @ForAll("takenSets") Set<String> taken) {
        assertThat(SellerCodeGenerator.generate(name, taken::contains)).matches(CODE);
    }

    @Property
    void resultIsNeverInTheTakenSet(@ForAll("names") String name, @ForAll("takenSets") Set<String> taken) {
        Set<String> blocked = new HashSet<>(taken);
        blocked.add(SellerCodeGenerator.slug(name));
        blocked.add(SellerCodeGenerator.slug(name) + "_2");
        assertThat(SellerCodeGenerator.generate(name, blocked::contains)).isNotIn(blocked);
    }

    @Property
    void isDeterministic(@ForAll("names") String name, @ForAll("takenSets") Set<String> taken) {
        assertThat(SellerCodeGenerator.generate(name, taken::contains))
                .isEqualTo(SellerCodeGenerator.generate(name, taken::contains));
    }

    @Property
    void freeBaseIsReturnedUnchanged(@ForAll("names") String name) {
        assertThat(SellerCodeGenerator.generate(name, c -> false)).isEqualTo(SellerCodeGenerator.slug(name));
    }
}
