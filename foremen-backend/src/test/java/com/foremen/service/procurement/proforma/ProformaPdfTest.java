package com.foremen.service.procurement.proforma;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/** FOR-11-03 task 3.3 (R4.5, R26.4): PDF label parity and the XHTML builder. */
class ProformaPdfTest {

    private final ProformaHtmlBuilder builder = new ProformaHtmlBuilder();

    private static ProformaDocModel model(String itemName, String note) {
        ProformaDocModel.Line line = new ProformaDocModel.Line(1, itemName, "SKU-1",
                new BigDecimal("2.500"), "m2", new BigDecimal("40.00"), new BigDecimal("23.00"),
                new BigDecimal("100.00"), new BigDecimal("23.00"), new BigDecimal("123.00"));
        return new ProformaDocModel("PF/2026/000123", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 8),
                "PLN", "Foremen sp. z o.o.", "ul. Testowa 1, Warszawa", "5250000000",
                "PL61 1090 1014 0000 0712 1981 2874", "Jan Kowalski", "ul. Klienta 2", null,
                List.of(line), new BigDecimal("100.00"), new BigDecimal("23.00"),
                new BigDecimal("123.00"), note);
    }

    private static void assertWellFormed(String xhtml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document d = f.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xhtml.getBytes(StandardCharsets.UTF_8)));
        assertThat(d.getDocumentElement().getLocalName()).isEqualTo("html");
    }

    @Test
    void labelBundlesDefineEveryKeyInBothLanguages() {
        ProformaPdfLabels pl = ProformaPdfLabels.forLanguage("pl");
        ProformaPdfLabels ru = ProformaPdfLabels.forLanguage("ru");
        assertThat(pl.keys()).containsExactlyInAnyOrderElementsOf(ProformaPdfLabels.KEYS);
        assertThat(ru.keys()).containsExactlyInAnyOrderElementsOf(pl.keys());
        for (String key : ProformaPdfLabels.KEYS) {
            assertThat(pl.get(key)).isNotBlank();
            assertThat(ru.get(key)).isNotBlank();
        }
        assertThat(ProformaPdfLabels.forLanguage(null)).isSameAs(pl);
        assertThat(ProformaPdfLabels.forLanguage("xx")).isSameAs(pl);
    }

    @Test
    void htmlIsWellFormedAndCarriesRequiredContent() throws Exception {
        String html = builder.build(model("Płytka <Cersanit> & \"Co\"", "Uwaga"), ProformaPdfLabels.forLanguage("pl"));
        assertWellFormed(html);
        assertThat(html)
                .contains("Proforma PF/2026/000123")
                .contains("01.03.2026").contains("08.03.2026")
                .contains("PL61 1090 1014 0000 0712 1981 2874")
                .contains("5250000000")
                .contains("Jan Kowalski")
                .contains("2,5")
                .contains("123,00 PLN")
                .contains("23%")
                .contains("Uwaga")
                .contains("Proforma nie jest fakturą VAT.")
                .contains("Płytka &lt;Cersanit&gt; &amp; &quot;Co&quot;");
    }

    @Test
    void russianDocumentUsesRussianLabelsAndStaysWellFormed() throws Exception {
        String html = builder.build(model("Плитка", null), ProformaPdfLabels.forLanguage("ru"));
        assertWellFormed(html);
        assertThat(html).contains("Проформа не является счётом-фактурой НДС.").contains("Продавец");
    }

    @Test
    void illegalXmlCharactersAreDropped() throws Exception {
        String html = builder.build(model("A\u0000B\u0008C", null), ProformaPdfLabels.forLanguage("pl"));
        assertWellFormed(html);
        assertThat(html).contains("ABC");
    }

    /** FOR-11-06 task 3.4 (R4.7): the payee account line keeps Polish letters of the seller block. */
    @Test
    void payeeAccountBlockKeepsPolishLetters() throws Exception {
        ProformaDocModel base = model("Płytka", null);
        ProformaDocModel pl = new ProformaDocModel(base.number(), base.issueDate(), base.dueDate(), "PLN",
                "Żółć Łódź sp. z o.o.", "ul. Gęślą Jaźń 1, Łódź", "5250000000",
                "PL61109010140000071219812874", "Zażółć Gęślą", "ul. Klienta 2", null,
                base.lines(), base.totalNet(), base.totalVat(), base.totalGross(), null);

        String html = builder.build(pl, ProformaPdfLabels.forLanguage("pl"));

        assertWellFormed(html);
        assertThat(html).contains("Żółć Łódź sp. z o.o.").contains("ul. Gęślą Jaźń 1, Łódź")
                .contains("PL61109010140000071219812874");
    }

    @Test
    void fileNameReplacesSlashes() {
        assertThat(ProformaPdfService.fileName("PF/2026/000123")).isEqualTo("PF-2026-000123.pdf");
    }
}
