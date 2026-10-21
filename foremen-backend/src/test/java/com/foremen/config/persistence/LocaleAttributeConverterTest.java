package com.foremen.config.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.Locale;

import static com.foremen.config.persistence.LocaleAttributeConverter.PL;
import static com.foremen.config.persistence.LocaleAttributeConverter.RU;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-06-12 task 2.3 (Requirements 12.1, 12.2) — unit tests for the shared
 * {@link LocaleAttributeConverter}.
 *
 * <p>The converter is the single {@link Locale} ⇄ stored lowercase language-tag representation used
 * across the codebase. These tests pin:
 * <ul>
 *   <li>write path — {@code PL → "pl"}, {@code RU → "ru"}, {@code null → null} (R12.1);</li>
 *   <li>read path — {@code "pl"/"ru" →} the matching {@link Locale}, and {@code null}/blank/
 *       unsupported ({@code "en"}) → PL {@link LocaleAttributeConverter#FALLBACK} (R12.2);</li>
 *   <li>round-trip — {@code "pl"}/{@code "ru"} survive a DB → Locale → DB round-trip unchanged,
 *       which is what keeps the existing {@code users.locale} column backwards compatible.</li>
 * </ul>
 */
class LocaleAttributeConverterTest {

    private final LocaleAttributeConverter converter = new LocaleAttributeConverter();

    @Nested
    @DisplayName("convertToDatabaseColumn (Locale -> stored language tag)")
    class ToDatabaseColumn {

        @Test
        @DisplayName("PL locale is written as \"pl\"")
        void plWrittenAsPl() {
            assertThat(converter.convertToDatabaseColumn(PL)).isEqualTo("pl");
        }

        @Test
        @DisplayName("RU locale is written as \"ru\"")
        void ruWrittenAsRu() {
            assertThat(converter.convertToDatabaseColumn(RU)).isEqualTo("ru");
        }

        @Test
        @DisplayName("null Locale is written as null")
        void nullWrittenAsNull() {
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
        }
    }

    @Nested
    @DisplayName("convertToEntityAttribute (stored value -> Locale)")
    class ToEntityAttribute {

        @Test
        @DisplayName("\"pl\" is read as the PL locale")
        void plReadAsPl() {
            assertThat(converter.convertToEntityAttribute("pl")).isEqualTo(PL);
        }

        @Test
        @DisplayName("\"ru\" is read as the RU locale")
        void ruReadAsRu() {
            assertThat(converter.convertToEntityAttribute("ru")).isEqualTo(RU);
        }

        @Test
        @DisplayName("null falls back to PL")
        void nullFallsBackToPl() {
            assertThat(converter.convertToEntityAttribute(null)).isEqualTo(PL);
        }

        @Test
        @DisplayName("blank falls back to PL")
        void blankFallsBackToPl() {
            assertThat(converter.convertToEntityAttribute("   ")).isEqualTo(PL);
        }

        @Test
        @DisplayName("unsupported \"en\" falls back to PL")
        void unsupportedEnFallsBackToPl() {
            assertThat(converter.convertToEntityAttribute("en")).isEqualTo(PL);
        }
    }

    @Nested
    @DisplayName("round-trip stored value <-> Locale")
    class RoundTrip {

        @Test
        @DisplayName("\"pl\" survives stored -> Locale -> stored unchanged")
        void plRoundTrip() {
            Locale parsed = converter.convertToEntityAttribute("pl");
            assertThat(converter.convertToDatabaseColumn(parsed)).isEqualTo("pl");
        }

        @Test
        @DisplayName("\"ru\" survives stored -> Locale -> stored unchanged")
        void ruRoundTrip() {
            Locale parsed = converter.convertToEntityAttribute("ru");
            assertThat(converter.convertToDatabaseColumn(parsed)).isEqualTo("ru");
        }
    }
}
