package com.foremen.service.ocr;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-07-04 task 6.11 — the per-line parse captures the invoice fields used by bank matching
 * ({@code supplierNip}, {@code invoiceNumber}, {@code invoiceDate}) tolerantly: an invalid NIP
 * checksum or an unparseable date yields {@code null}, and the existing line extraction is unchanged.
 *
 * <p>Validates: Requirements 18.2, 18.4
 */
class OcrHelpersInvoiceFieldsTest {

    /** A checksum-valid NIP (weights 6,5,7,2,3,4,5,6,7 mod 11 → check digit 4). */
    private static final String VALID_NIP = "5260250274";

    private static String response(String nip, String number, String date) {
        return """
                ```json
                {"supplierName":"Castorama","documentTotal":"-123.45",
                 "supplierNip":%s,"invoiceNumber":%s,"invoiceDate":%s,
                 "lines":[{"rawName":"Klej 25kg","quantity":2,"unitHint":"szt",
                           "unitPrice":10.5,"lineTotal":21,"manufacturerHint":"Atlas"},
                          {"rawName":"  ","quantity":1}]}
                ```
                """.formatted(nip, number, date);
    }

    @Test
    @DisplayName("captures a valid NIP, the invoice number and the ISO invoice date")
    void capturesInvoiceFields() {
        OcrParse parse = OcrHelpers.parsePerLineResponse(
                response("\"" + VALID_NIP + "\"", "\" FV/123/2024 \"", "\"2024-03-15\""));

        assertThat(parse).isNotNull();
        assertThat(parse.supplierNip()).isEqualTo(VALID_NIP);
        assertThat(parse.invoiceNumber()).isEqualTo("FV/123/2024");
        assertThat(parse.invoiceDate()).isEqualTo(LocalDate.of(2024, 3, 15));
        // The existing extraction is unchanged.
        assertThat(parse.supplierName()).isEqualTo("Castorama");
        assertThat(parse.documentTotal()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(parse.lines()).hasSize(1);
        assertThat(parse.lines().get(0).rawName()).isEqualTo("Klej 25kg");
        assertThat(parse.lines().get(0).lineTotal()).isEqualByComparingTo("21");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"526-025-02-74\"", "\"PL 526 025 02 74\"", "\"pl5260250274\"",
            "\"526.025.02.74\"", "5260250274"})
    @DisplayName("normalizes a valid NIP printed with separators / PL prefix / as a number")
    void normalizesFormattedNip(String nipJson) {
        OcrParse parse = OcrHelpers.parsePerLineResponse(response(nipJson, "null", "null"));
        assertThat(parse.supplierNip()).isEqualTo(VALID_NIP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"5260250275\"", "\"526025027\"", "\"52602502740\"", "\"ABCDEFGHIJ\"",
            "\"\"", "null", "{}"})
    @DisplayName("an invalid / missing NIP is captured as null")
    void invalidNipIsNull(String nipJson) {
        OcrParse parse = OcrHelpers.parsePerLineResponse(response(nipJson, "null", "null"));
        assertThat(parse).isNotNull();
        assertThat(parse.supplierNip()).isNull();
        assertThat(parse.lines()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"15.03.2024\"", "\"2024-02-30\"", "\"yesterday\"", "\"\"", "20240315",
            "null"})
    @DisplayName("an unparseable / missing invoice date is captured as null")
    void invalidDateIsNull(String dateJson) {
        OcrParse parse = OcrHelpers.parsePerLineResponse(response("null", "null", dateJson));
        assertThat(parse.invoiceDate()).isNull();
        assertThat(parse.lines()).hasSize(1);
    }

    @Test
    @DisplayName("an ISO date-time keeps only its date part")
    void isoDateTimeKeepsDate() {
        OcrParse parse = OcrHelpers.parsePerLineResponse(
                response("null", "null", "\"2024-03-15T10:20:00\""));
        assertThat(parse.invoiceDate()).isEqualTo(LocalDate.of(2024, 3, 15));
    }

    @Test
    @DisplayName("a blank or over-long (> 64 chars) invoice number is captured as null")
    void invoiceNumberBounds() {
        assertThat(OcrHelpers.parsePerLineResponse(response("null", "\"   \"", "null"))
                .invoiceNumber()).isNull();
        String max = "F".repeat(64);
        assertThat(OcrHelpers.parsePerLineResponse(response("null", "\"" + max + "\"", "null"))
                .invoiceNumber()).isEqualTo(max);
        assertThat(OcrHelpers.parsePerLineResponse(response("null", "\"" + max + "X\"", "null"))
                .invoiceNumber()).isNull();
    }

    @Test
    @DisplayName("a response without the new fields (FOR-06-03 shape) and a bare array parse as before")
    void legacyShapes() {
        OcrParse legacy = OcrHelpers.parsePerLineResponse(
                "{\"supplierName\":\"Leroy\",\"documentTotal\":5,\"lines\":[{\"rawName\":\"Wkręt\"}]}");
        assertThat(legacy.lines()).hasSize(1);
        assertThat(legacy.supplierName()).isEqualTo("Leroy");
        assertThat(legacy.supplierNip()).isNull();
        assertThat(legacy.invoiceNumber()).isNull();
        assertThat(legacy.invoiceDate()).isNull();

        OcrParse bare = OcrHelpers.parsePerLineResponse("[{\"rawName\":\"Wkręt\"}]");
        assertThat(bare.lines()).hasSize(1);
        assertThat(bare.supplierNip()).isNull();
        assertThat(bare.invoiceDate()).isNull();
    }

    @Test
    @DisplayName("the per-line prompt requests the three invoice fields")
    void promptRequestsInvoiceFields() {
        assertThat(OcrHelpers.PER_LINE_PARSE_PROMPT)
                .contains("\"supplierNip\"", "\"invoiceNumber\"", "\"invoiceDate\"", "YYYY-MM-DD");
    }
}
