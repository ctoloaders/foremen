package com.foremen.service.procurement.proforma;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/**
 * FOR-11-03 (R4.4, R4.5): pure builder of the well-formed XHTML a proforma PDF is rendered from.
 * No Spring dependencies beyond the stereotype, no I/O: the same {@link ProformaDocModel} and labels
 * always give the same markup. Every user value is escaped and stripped of characters XML forbids.
 */
@Component
public class ProformaHtmlBuilder {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public String build(ProformaDocModel doc, ProformaPdfLabels labels) {
        StringBuilder h = new StringBuilder(4096);
        h.append("<!DOCTYPE html>")
                .append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"utf-8\"/>")
                .append("<title>").append(esc(doc.number())).append("</title>")
                .append("<style>")
                .append("body{font-family:'Liberation Sans',sans-serif;font-size:11px;margin:30px;color:#000;}")
                .append("h1{font-size:20px;margin:0 0 4px 0;}")
                .append(".meta{margin:2px 0;}")
                .append(".parties{width:100%;margin:16px 0;border-collapse:collapse;}")
                .append(".parties td{width:50%;vertical-align:top;padding:0 12px 0 0;}")
                .append(".party-title{font-weight:bold;margin-bottom:4px;}")
                .append("table.lines{width:100%;border-collapse:collapse;margin-top:12px;}")
                .append("table.lines th,table.lines td{border:1px solid #444;padding:3px 4px;}")
                .append("table.lines th{background:#eee;text-align:left;}")
                .append(".num{text-align:right;}")
                .append("table.totals{margin-top:12px;border-collapse:collapse;margin-left:auto;}")
                .append("table.totals td{padding:2px 6px;}")
                .append(".total-gross{font-weight:bold;font-size:13px;}")
                .append(".note{margin-top:20px;font-style:italic;}")
                .append("</style></head><body>");

        h.append("<h1>").append(esc(labels.get("title"))).append(' ').append(esc(doc.number())).append("</h1>");
        h.append("<div class=\"meta\">").append(esc(labels.get("issueDate"))).append(": ")
                .append(date(doc.issueDate())).append("</div>");
        h.append("<div class=\"meta\">").append(esc(labels.get("dueDate"))).append(": ")
                .append(date(doc.dueDate())).append("</div>");

        h.append("<table class=\"parties\"><tr><td>");
        party(h, labels.get("seller"), doc.sellerName(), doc.sellerAddress(), doc.sellerNip(), labels);
        h.append("<div>").append(esc(labels.get("bankAccount"))).append(": ")
                .append(esc(doc.sellerBankAccount())).append("</div>");
        h.append("</td><td>");
        party(h, labels.get("buyer"), doc.buyerName(), doc.buyerAddress(), doc.buyerNip(), labels);
        h.append("</td></tr></table>");

        h.append("<div class=\"meta\"><b>").append(esc(labels.get("paymentTitle"))).append(":</b> ")
                .append(esc(doc.paymentTitle(labels.get("title")))).append("</div>");

        lines(h, doc, labels);
        totals(h, doc, labels);

        if (doc.note() != null && !doc.note().isBlank()) {
            h.append("<div class=\"meta\">").append(esc(doc.note())).append("</div>");
        }
        h.append("<div class=\"note\">").append(esc(labels.get("notVatInvoice"))).append("</div>");
        h.append("</body></html>");
        return h.toString();
    }

    private void party(StringBuilder h, String title, String name, String address, String nip,
                       ProformaPdfLabels labels) {
        h.append("<div class=\"party-title\">").append(esc(title)).append("</div>");
        h.append("<div>").append(esc(name)).append("</div>");
        if (address != null && !address.isBlank()) {
            h.append("<div>").append(esc(address)).append("</div>");
        }
        if (nip != null && !nip.isBlank()) {
            h.append("<div>").append(esc(labels.get("nip"))).append(": ").append(esc(nip)).append("</div>");
        }
    }

    private void lines(StringBuilder h, ProformaDocModel doc, ProformaPdfLabels labels) {
        h.append("<table class=\"lines\"><thead><tr>")
                .append("<th>#</th>");
        for (String key : new String[] {"colName", "colSku", "colQty", "colUnit", "colUnitNet", "colVat",
                "colNet", "colVatAmount", "colGross"}) {
            h.append("<th>").append(esc(labels.get(key))).append("</th>");
        }
        h.append("</tr></thead><tbody>");
        for (ProformaDocModel.Line l : doc.lines()) {
            h.append("<tr><td>").append(l.lineNo()).append("</td>")
                    .append("<td>").append(esc(l.name())).append("</td>")
                    .append("<td>").append(esc(l.sku())).append("</td>")
                    .append("<td class=\"num\">").append(qty(l.quantity())).append("</td>")
                    .append("<td>").append(esc(l.unit())).append("</td>")
                    .append("<td class=\"num\">").append(money(l.unitNet())).append("</td>")
                    .append("<td class=\"num\">").append(percent(l.vatPercent())).append("</td>")
                    .append("<td class=\"num\">").append(money(l.net())).append("</td>")
                    .append("<td class=\"num\">").append(money(l.vat())).append("</td>")
                    .append("<td class=\"num\">").append(money(l.gross())).append("</td></tr>");
        }
        h.append("</tbody></table>");
    }

    private void totals(StringBuilder h, ProformaDocModel doc, ProformaPdfLabels labels) {
        Map<BigDecimal, BigDecimal[]> byRate = new TreeMap<>();
        for (ProformaDocModel.Line l : doc.lines()) {
            BigDecimal key = l.vatPercent().setScale(2, RoundingMode.HALF_UP);
            BigDecimal[] sums = byRate.computeIfAbsent(key,
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            sums[0] = sums[0].add(l.net());
            sums[1] = sums[1].add(l.vat());
            sums[2] = sums[2].add(l.gross());
        }
        h.append("<div class=\"meta\"><b>").append(esc(labels.get("vatSummary"))).append("</b></div>");
        h.append("<table class=\"lines\" style=\"width:60%\"><thead><tr>")
                .append("<th>").append(esc(labels.get("colVat"))).append("</th>")
                .append("<th>").append(esc(labels.get("colNet"))).append("</th>")
                .append("<th>").append(esc(labels.get("colVatAmount"))).append("</th>")
                .append("<th>").append(esc(labels.get("colGross"))).append("</th>")
                .append("</tr></thead><tbody>");
        byRate.forEach((rate, s) -> h.append("<tr><td class=\"num\">").append(percent(rate)).append("</td>")
                .append("<td class=\"num\">").append(money(s[0])).append("</td>")
                .append("<td class=\"num\">").append(money(s[1])).append("</td>")
                .append("<td class=\"num\">").append(money(s[2])).append("</td></tr>"));
        h.append("</tbody></table>");

        String cur = esc(doc.currency());
        h.append("<table class=\"totals\">")
                .append("<tr><td>").append(esc(labels.get("totalNet"))).append("</td><td class=\"num\">")
                .append(money(doc.totalNet())).append(' ').append(cur).append("</td></tr>")
                .append("<tr><td>").append(esc(labels.get("totalVat"))).append("</td><td class=\"num\">")
                .append(money(doc.totalVat())).append(' ').append(cur).append("</td></tr>")
                .append("<tr class=\"total-gross\"><td>").append(esc(labels.get("totalGross")))
                .append("</td><td class=\"num\">")
                .append(money(doc.totalGross())).append(' ').append(cur).append("</td></tr>")
                .append("</table>");
    }

    // --- formatting (locale independent; PL and RU both use the decimal comma) ---

    static String money(BigDecimal v) {
        return v == null ? "" : v.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    static String qty(BigDecimal v) {
        if (v == null) {
            return "";
        }
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() < 0) {
            s = s.setScale(0);
        }
        return s.toPlainString().replace('.', ',');
    }

    static String percent(BigDecimal v) {
        if (v == null) {
            return "";
        }
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() < 0) {
            s = s.setScale(0);
        }
        return s.toPlainString().replace('.', ',') + "%";
    }

    private static String date(LocalDate d) {
        return d == null ? "" : DATE.format(d);
    }

    /** XML-escapes and drops characters that are illegal in XML 1.0. */
    static String esc(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        text.codePoints().forEach(cp -> {
            if (cp == '&') {
                out.append("&amp;");
            } else if (cp == '<') {
                out.append("&lt;");
            } else if (cp == '>') {
                out.append("&gt;");
            } else if (cp == '"') {
                out.append("&quot;");
            } else if (cp == '\'') {
                out.append("&#39;");
            } else if (cp == 0x9 || cp == 0xA || cp == 0xD
                    || (cp >= 0x20 && cp <= 0xD7FF)
                    || (cp >= 0xE000 && cp <= 0xFFFD)
                    || (cp >= 0x10000 && cp <= 0x10FFFF)) {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }
}
