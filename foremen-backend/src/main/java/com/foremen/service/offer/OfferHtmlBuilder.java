package com.foremen.service.offer;

import static com.foremen.service.procurement.proforma.ProformaHtmlBuilder.esc;
import static com.foremen.service.procurement.proforma.ProformaHtmlBuilder.money;
import static com.foremen.service.procurement.proforma.ProformaHtmlBuilder.qty;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.service.offer.OfferSnapshotViews.GroupView;
import com.foremen.service.offer.OfferSnapshotViews.RowView;
import com.foremen.service.offer.OfferSnapshotViews.SnapshotView;

/**
 * FOR-05-07b task 3.6: pure builder of the XHTML an offer PDF is rendered from. The input is the
 * already redacted {@link SnapshotView}, so the document cannot carry base prices or margins.
 */
@Component
public class OfferHtmlBuilder {

    /** Header facts that are not part of the snapshot view. */
    public record Header(String projectName, String projectAddress, String date) {
    }

    public String build(SnapshotView view, Header header, boolean ru) {
        Labels l = ru ? Labels.RU : Labels.PL;
        List<OfferSnapshot.Room> rooms = view.rooms() != null ? view.rooms() : List.of();
        StringBuilder h = new StringBuilder(4096);
        h.append("<!DOCTYPE html>")
                .append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"utf-8\"/>")
                .append("<title>").append(esc(view.name())).append("</title>")
                .append("<style>")
                .append("@page{size:A4 landscape;margin:20px;}")
                .append("body{font-family:'Liberation Sans',sans-serif;font-size:10px;color:#000;}")
                .append("h1{font-size:18px;margin:0 0 4px 0;}")
                .append(".meta{margin:2px 0;}")
                .append("table.m{width:100%;border-collapse:collapse;margin-top:12px;}")
                .append("table.m th,table.m td{border:1px solid #444;padding:3px 4px;}")
                .append("table.m th{background:#eee;text-align:left;}")
                .append("tr.grp td{background:#ddd;font-weight:bold;}")
                .append(".num{text-align:right;}")
                .append("table.totals{margin-top:12px;border-collapse:collapse;margin-left:auto;}")
                .append("table.totals td{padding:2px 6px;}")
                .append(".gross{font-weight:bold;font-size:12px;}")
                .append("</style></head><body>");

        h.append("<h1>").append(esc(l.title)).append(": ").append(esc(view.name())).append("</h1>");
        meta(h, l.project, header.projectName());
        meta(h, l.address, header.projectAddress());
        meta(h, l.date, header.date());
        if (view.appliedPackageCode() != null) {
            meta(h, l.pkg, view.appliedPackageName());
        }

        h.append("<table class=\"m\"><thead><tr><th>").append(esc(l.work)).append("</th><th>")
                .append(esc(l.unit)).append("</th>");
        for (OfferSnapshot.Room r : rooms) {
            h.append("<th class=\"num\">").append(esc(r.label())).append("</th>");
        }
        h.append("<th class=\"num\">").append(esc(l.qty)).append("</th><th class=\"num\">")
                .append(esc(l.unitPrice)).append("</th><th class=\"num\">").append(esc(l.valueNet))
                .append("</th></tr></thead><tbody>");
        int cols = rooms.size() + 5;
        if (view.groups() != null) {
            for (GroupView g : view.groups()) {
                h.append("<tr class=\"grp\"><td colspan=\"").append(cols).append("\">")
                        .append(esc(g.name())).append("</td></tr>");
                if (g.rows() != null) {
                    for (RowView r : g.rows()) {
                        row(h, r, rooms);
                    }
                }
            }
        }
        h.append("</tbody></table>");

        h.append("<table class=\"totals\">")
                .append("<tr><td>").append(esc(l.totalNet)).append("</td><td class=\"num\">")
                .append(money(view.totalNet())).append("</td></tr>")
                .append("<tr><td>").append(esc(l.vat)).append(" ").append(percent(view.vatRatePct()))
                .append("</td><td class=\"num\">").append(money(view.totalVat())).append("</td></tr>")
                .append("<tr class=\"gross\"><td>").append(esc(l.totalGross)).append("</td><td class=\"num\">")
                .append(money(view.totalGross())).append("</td></tr></table>");
        h.append("</body></html>");
        return h.toString();
    }

    private static void row(StringBuilder h, RowView r, List<OfferSnapshot.Room> rooms) {
        Map<Long, BigDecimal> byRoom = new HashMap<>();
        if (r.cells() != null) {
            for (OfferSnapshotViews.CellView c : r.cells()) {
                byRoom.merge(c.roomId(), c.quantity() != null ? c.quantity() : BigDecimal.ZERO, BigDecimal::add);
            }
        }
        h.append("<tr><td>").append(esc(r.name())).append("</td><td>").append(esc(r.unitName())).append("</td>");
        for (OfferSnapshot.Room room : rooms) {
            BigDecimal q = byRoom.get(room.id());
            h.append("<td class=\"num\">").append(q != null ? qty(q) : "").append("</td>");
        }
        h.append("<td class=\"num\">").append(qty(r.quantity())).append("</td><td class=\"num\">")
                .append(money(r.unitPrice())).append("</td><td class=\"num\">")
                .append(money(r.valueNet())).append("</td></tr>");
    }

    private static void meta(StringBuilder h, String label, String value) {
        if (value != null && !value.isBlank()) {
            h.append("<div class=\"meta\"><b>").append(esc(label)).append(":</b> ").append(esc(value))
                    .append("</div>");
        }
    }

    private static String percent(BigDecimal v) {
        return v == null ? "" : qty(v) + "%";
    }

    private record Labels(String title, String project, String address, String date, String pkg, String work,
                          String unit, String qty, String unitPrice, String valueNet, String totalNet,
                          String vat, String totalGross) {
        static final Labels PL = new Labels("Oferta", "Projekt", "Adres", "Data", "Pakiet", "Praca", "J.m.",
                "Ilość", "Cena jedn. netto", "Wartość netto", "Razem netto", "VAT", "Razem brutto");
        static final Labels RU = new Labels("Оферта", "Проект", "Адрес", "Дата", "Пакет", "Работа", "Ед.",
                "Кол-во", "Цена за ед. нетто", "Сумма нетто", "Итого нетто", "НДС", "Итого брутто");
    }
}
