package com.foremen.service.procurement.proforma;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * FOR-11-03 (R4.5, R26.4): the fixed text of the proforma PDF in {@code pl} (default) and {@code ru},
 * read from the UTF-8 resource files {@code proforma_labels_pl.properties} and
 * {@code proforma_labels_ru.properties}. A key missing from either file fails class loading, so a
 * label can never be silently absent from a PDF.
 */
public final class ProformaPdfLabels {

    /** Every key a bundle must define (design, "PDF labels"). */
    public static final List<String> KEYS = List.of(
            "title", "seller", "buyer", "nip", "bankAccount", "issueDate", "dueDate",
            "paymentTitle", "colName", "colSku", "colQty", "colUnit", "colUnitNet", "colVat",
            "colNet", "colVatAmount", "colGross", "totalNet", "totalVat", "totalGross",
            "vatSummary", "notVatInvoice");

    private static final ProformaPdfLabels PL = load("pl");
    private static final ProformaPdfLabels RU = load("ru");

    private final String language;
    private final Map<String, String> values;

    private ProformaPdfLabels(String language, Map<String, String> values) {
        this.language = language;
        this.values = values;
    }

    /** {@code ru} gives the Russian bundle; anything else (including null) the Polish default. */
    public static ProformaPdfLabels forLanguage(String language) {
        return "ru".equalsIgnoreCase(language) ? RU : PL;
    }

    public String language() {
        return language;
    }

    public String get(String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Unknown proforma PDF label: " + key);
        }
        return value;
    }

    /** All keys defined by the bundle (used by the parity test). */
    public java.util.Set<String> keys() {
        return values.keySet();
    }

    private static ProformaPdfLabels load(String language) {
        String path = "/proforma_labels_" + language + ".properties";
        Properties props = new Properties();
        try (InputStream in = ProformaPdfLabels.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + path);
            }
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
        Map<String, String> map = props.stringPropertyNames().stream()
                .collect(Collectors.toUnmodifiableMap(k -> k, props::getProperty));
        for (String key : KEYS) {
            if (!map.containsKey(key) || map.get(key).isBlank()) {
                throw new IllegalStateException("Resource " + path + " lacks label '" + key + "'");
            }
        }
        return new ProformaPdfLabels(language, map);
    }
}
