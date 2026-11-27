package com.foremen.service.estimateai.chat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.foremen.service.estimate.CellAiMeta;
import com.foremen.service.offer.OfferSnapshot;

/**
 * FOR-05-04 (design B8 step 2, Requirement 11.1) — compact, size-bounded text rendering of an
 * {@link OfferSnapshot} for the chat model: rooms with ids, then per category one line per work row with
 * code, name, unit, unit price (and its override), per-room quantities, line total and comment. Cells that
 * carry an AI explanation get a short form of it (status, volume source, trimmed rationale). The price
 * block is shown once per row.
 *
 * <p>Pure: no JPA, no randomness. When the output would exceed {@code maxChars}, whole rows are dropped
 * from the end and a line states how many, so the model can narrow the view with the {@code filter}.</p>
 */
public final class SnapshotTextRenderer {

    public static final int DEFAULT_MAX_CHARS = 24_000;
    static final int RATIONALE_CHARS = 70;
    static final int COMMENT_CHARS = 60;

    private SnapshotTextRenderer() {
    }

    /** @param workCodes work code by work item id (the snapshot rows carry no code) */
    public static String render(OfferSnapshot snapshot, Map<Long, String> workCodes, String filter, int maxChars) {
        if (snapshot == null) {
            return "OFFER: empty";
        }
        int limit = maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;
        String f = filter == null || filter.isBlank() ? null : filter.trim().toLowerCase(Locale.ROOT);
        Map<Long, String> codes = workCodes == null ? Map.of() : workCodes;

        StringBuilder head = new StringBuilder();
        head.append("OFFER totals: net=").append(num(snapshot.totalNet()))
                .append(" vat=").append(num(snapshot.totalVat()))
                .append(" gross=").append(num(snapshot.totalGross()))
                .append(" vatRate=").append(num(snapshot.vatRatePct())).append('%');
        if (snapshot.appliedPackageCode() != null) {
            head.append(" package=").append(snapshot.appliedPackageCode());
        }
        head.append('\n');
        Map<Long, String> roomLabels = new LinkedHashMap<>();
        if (snapshot.rooms() != null) {
            snapshot.rooms().forEach(r -> roomLabels.put(r.id(), r.label()));
        }
        List<String> rooms = new ArrayList<>();
        roomLabels.forEach((id, label) -> rooms.add(id + "=" + label));
        head.append("ROOMS: ").append(String.join("; ", rooms)).append('\n');

        StringBuilder body = new StringBuilder();
        int total = 0;
        int shown = 0;
        boolean full = false;
        if (snapshot.groups() != null) {
            for (OfferSnapshot.Group g : snapshot.groups()) {
                if (g.rows() == null || g.rows().isEmpty()) {
                    continue;
                }
                String catName = firstNonBlank(g.nameRu(), g.namePl());
                boolean catMatch = f != null && contains(catName, f);
                StringBuilder section = new StringBuilder();
                for (OfferSnapshot.Row r : g.rows()) {
                    String code = codes.get(r.workItemId());
                    if (f != null && !catMatch && !rowMatches(r, code, roomLabels, f)) {
                        continue;
                    }
                    total++;
                    if (full) {
                        continue;
                    }
                    String line = row(r, code);
                    int header = section.length() == 0 ? catName.length() + 4 : 0;
                    if (head.length() + body.length() + section.length() + header + line.length() + 1 > limit) {
                        full = true;
                        continue;
                    }
                    if (section.length() == 0) {
                        section.append("## ").append(catName).append('\n');
                    }
                    section.append(line).append('\n');
                    shown++;
                }
                body.append(section);
            }
        }
        StringBuilder out = new StringBuilder(head).append(body);
        if (total == 0) {
            out.append(f == null ? "(no works)" : "(no rows match the filter)").append('\n');
        }
        if (total > shown) {
            out.append("... ").append(total - shown).append(" more rows not shown (of ").append(total)
                    .append("); call get_offer_state with a narrower filter\n");
        }
        return out.toString();
    }

    private static String row(OfferSnapshot.Row r, String code) {
        StringBuilder s = new StringBuilder();
        s.append(code == null ? "?" : code).append(' ')
                .append(firstNonBlank(r.nameRu(), r.namePl())).append(" [")
                .append(r.unitCode() == null ? "" : r.unitCode()).append("] price=").append(num(r.unitPrice()));
        if (r.unitPriceOverride() != null) {
            s.append(" (base ").append(num(r.baseUnitPrice())).append(", override ")
                    .append(num(r.unitPriceOverride())).append(')');
        }
        s.append(" qty=").append(num(r.quantity())).append(" net=").append(num(r.valueNet())).append(" |");
        CellAiMeta priceMeta = null;
        if (r.cells() != null) {
            for (OfferSnapshot.Cell c : r.cells()) {
                s.append(' ').append(c.roomId()).append('=').append(num(c.quantity()));
                CellAiMeta ai = aiOf(c);
                if (ai != null) {
                    s.append(shortForm(ai));
                    if (priceMeta == null && ai.price() != null) {
                        priceMeta = ai;
                    }
                }
            }
        }
        if (priceMeta != null) {
            CellAiMeta.Price p = priceMeta.price();
            s.append(" | price-ai:");
            if (p.pct() != null) {
                s.append(' ').append(num(p.pct())).append('%');
            }
            if (p.scope() != null) {
                s.append(' ').append(p.scope());
            }
            if (p.reason() != null) {
                s.append(" \"").append(cut(p.reason(), RATIONALE_CHARS)).append('"');
            }
        }
        if (r.comment() != null && !r.comment().isBlank()) {
            s.append(" | comment: \"").append(cut(r.comment(), COMMENT_CHARS)).append('"');
        }
        return s.toString();
    }

    /** Short form of a cell explanation, e.g. {@code {AI ASSUMED vol=MODEL_OVERRIDE f=12.4 "reason"}}. */
    private static String shortForm(CellAiMeta ai) {
        StringBuilder s = new StringBuilder(" {AI");
        if (ai.status() != null) {
            s.append(' ').append(ai.status());
        }
        if (ai.volume() != null) {
            if (ai.volume().source() != null) {
                s.append(" vol=").append(ai.volume().source());
            }
            if (ai.volume().formulaVolume() != null && ai.volume().correction() != null) {
                s.append(" f=").append(num(ai.volume().formulaVolume()));
            }
        }
        if (ai.rationale() != null && !ai.rationale().isBlank()) {
            s.append(" \"").append(cut(ai.rationale(), RATIONALE_CHARS)).append('"');
        }
        return s.append('}').toString();
    }

    private static boolean rowMatches(OfferSnapshot.Row r, String code, Map<Long, String> rooms, String f) {
        if (contains(code, f) || contains(r.nameRu(), f) || contains(r.namePl(), f)) {
            return true;
        }
        if (r.cells() != null) {
            for (OfferSnapshot.Cell c : r.cells()) {
                if (String.valueOf(c.roomId()).equals(f) || contains(rooms.get(c.roomId()), f)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Cell AI explanation; {@code null} when the cell carries none. */
    static CellAiMeta aiOf(OfferSnapshot.Cell cell) {
        return cell.ai();
    }

    private static boolean contains(String s, String f) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(f);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "" : b);
    }

    private static String cut(String s, int max) {
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static String num(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }
}
