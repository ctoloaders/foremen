package com.foremen.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SellerCodeGeneratorTest {

    @Test
    void foldsPolishDiacriticsAndSlugifies() {
        assertThat(SellerCodeGenerator.slug("Żółć Hurtownia  Śląska & Co."))
                .isEqualTo("zolc_hurtownia_slaska_co");
    }

    @Test
    void transliteratesCyrillic() {
        assertThat(SellerCodeGenerator.slug("Строй Маркет")).isEqualTo("stroy_market");
    }

    @Test
    void emptyOrSymbolOnlyNameFallsBack() {
        assertThat(SellerCodeGenerator.slug("!!!")).isEqualTo("seller");
        assertThat(SellerCodeGenerator.slug(null)).isEqualTo("seller");
    }

    @Test
    void longNameIsTrimmedToBaseMax() {
        String slug = SellerCodeGenerator.slug("a".repeat(150));
        assertThat(slug).hasSize(90);
    }

    @Test
    void collisionsGetNumericSuffix() {
        Set<String> taken = Set.of("castorama", "castorama_2");
        assertThat(SellerCodeGenerator.generate("Castorama", taken::contains)).isEqualTo("castorama_3");
        assertThat(SellerCodeGenerator.generate("Leroy", taken::contains)).isEqualTo("leroy");
    }

    @Test
    void outputAlwaysMatchesCodePattern() {
        for (String n : new String[] {"Ąę ćńóśźż", "  --  ", "ŁÓDŹ 24/7", "Ёж", "x".repeat(300)}) {
            assertThat(SellerCodeGenerator.generate(n, c -> false)).matches("^[a-z0-9_]{1,100}$");
        }
    }
}
