package com.foremen.service.estimateai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.foremen.service.estimateai.EstimatePlan.Note;
import com.foremen.service.estimateai.PlanCatalog.CatalogWork;
import com.foremen.service.estimateai.PlanCatalog.RoomFacts;

/**
 * FOR-05-04 (design B5, Requirements 5.3, 5.5, 6.5) — pure resolver of the model's raw plan.
 *
 * <ul>
 *   <li>{@code workCode} to active catalog work by exact code; unknown or inactive gives {@code UNKNOWN_CODE};
 *   <li>no positive price gives {@code NO_PRICE}; a percentage unit gives {@code UNSUPPORTED_UNIT}
 *       (never a zero line);
 *   <li>negative volumes are dropped ({@code NEGATIVE_VOLUME}), the work stays and the engine decides;
 *   <li>duplicate {@code (work, room)} pairs are merged, last wins ({@code DUPLICATE_WORK_ROOM}).
 * </ul>
 *
 * Tolerant of any JSON shape: never throws.
 */
public final class PlanResolver {

    static final int MAX_TEXT = 500;

    private PlanResolver() {
    }

    public static EstimatePlan resolve(JsonNode raw, List<RoomFacts> rooms, Map<String, CatalogWork> catalog) {
        List<RoomFacts> rs = rooms == null ? List.of() : rooms;
        Map<String, CatalogWork> cat = catalog == null ? Map.of() : catalog;
        Map<Long, RoomFacts> roomById = new LinkedHashMap<>();
        for (RoomFacts r : rs) {
            if (r != null && r.id() != null) {
                roomById.put(r.id(), r);
            }
        }
        List<ReportWarning> warnings = new ArrayList<>();
        List<Unpriced> unpriced = new ArrayList<>();
        List<PlannedWork> planned = new ArrayList<>();

        for (JsonNode w : array(raw, "works")) {
            if (w.isObject()) {
                resolveWork(w, roomById, cat, planned, unpriced, warnings);
            }
        }
        for (JsonNode u : array(raw, "unpriced")) {
            if (!u.isObject()) {
                continue;
            }
            String name = text(u, "name");
            if (name == null) {
                name = text(u, "workCode");
            }
            if (name == null) {
                continue;
            }
            String reason = text(u, "reason");
            unpriced.add(new Unpriced(name, text(u, "unit"), decimal(u.get("estimatedVolume")),
                    reason == null ? WarningCodes.NOT_IN_CATALOG : reason, sourceText(u.get("source")),
                    text(u, "workCode")));
        }

        Merged merged = merge(planned);
        warnings.addAll(merged.warnings());
        List<PlannedWork> works = merged.works();
        warnings.addAll(PlanValidator.validate(works, rs, cat));

        return new EstimatePlan(works, notes(raw, "assumptions"), notes(raw, "questions"), unpriced, warnings);
    }

    /** Result of {@link #merge}. */
    public record Merged(List<PlannedWork> works, List<ReportWarning> warnings) {
    }

    /**
     * Merges duplicate {@code (workCode, roomId)} pairs: the last occurrence wins, at the position of the
     * first. Idempotent: merging the output again changes nothing and yields no warnings.
     */
    public static Merged merge(List<PlannedWork> works) {
        Map<String, PlannedWork> byKey = new LinkedHashMap<>();
        List<ReportWarning> warnings = new ArrayList<>();
        if (works != null) {
            for (PlannedWork w : works) {
                if (w == null) {
                    continue;
                }
                String key = w.workCode() + "|" + w.roomId();
                if (byKey.containsKey(key)) {
                    warnings.add(ReportWarning.warning(WarningCodes.DUPLICATE_WORK_ROOM, w.roomId(), w.workCode(),
                            Map.of()));
                }
                byKey.put(key, w);
            }
        }
        return new Merged(new ArrayList<>(byKey.values()), warnings);
    }

    private static void resolveWork(JsonNode w, Map<Long, RoomFacts> roomById, Map<String, CatalogWork> cat,
                                    List<PlannedWork> planned, List<Unpriced> unpriced,
                                    List<ReportWarning> warnings) {
        String code = text(w, "workCode");
        if (code == null) {
            return;
        }
        List<Long> roomIds = roomIds(w);
        Map<Long, JsonNode> volumes = volumesByRoom(w);
        BigDecimal totalVolume = null;
        for (JsonNode v : volumes.values()) {
            BigDecimal d = decimal(v.get("value"));
            if (d != null && d.signum() >= 0) {
                totalVolume = totalVolume == null ? d : totalVolume.add(d);
            }
        }

        CatalogWork cw = cat.get(code);
        String failure = null;
        if (cw == null || !cw.active()) {
            failure = WarningCodes.UNKNOWN_CODE;
        } else if (cw.percentageUnit()) {
            failure = WarningCodes.UNSUPPORTED_UNIT;
        } else if (cw.price() == null || cw.price().signum() <= 0) {
            failure = WarningCodes.NO_PRICE;
        }
        if (failure != null) {
            String name = cw != null && cw.name() != null ? cw.name() : firstNonNull(text(w, "name"), code);
            unpriced.add(new Unpriced(name, firstUnit(volumes), totalVolume, failure, sourceText(w.get("source")),
                    code));
            return;
        }

        String status = text(w, "status");
        BigDecimal confidence = decimal(w.get("confidence"));
        String source = sourceText(w.get("source"));
        String alt = text(w, "alternativeGroup");
        String workRationale = text(w, "rationale");
        String workReason = text(w, "volumeReason");

        for (Long roomId : roomIds) {
            if (!roomById.containsKey(roomId)) {
                warnings.add(ReportWarning.warning(WarningCodes.UNKNOWN_ROOM, roomId, code, Map.of()));
                continue;
            }
            JsonNode v = volumes.get(roomId);
            BigDecimal volume = v == null ? null : decimal(v.get("value"));
            if (volume != null && volume.signum() < 0) {
                warnings.add(ReportWarning.warning(WarningCodes.NEGATIVE_VOLUME, roomId, code,
                        Map.of("volume", volume)));
                volume = null;
            }
            String rationale = firstNonNull(v == null ? null : text(v, "rationale"), workRationale);
            String reason = firstNonNull(v == null ? null : text(v, "volumeReason"), workReason);
            planned.add(new PlannedWork(code, cw.id(), roomId, volume,
                    v == null ? null : text(v, "unit"), v == null ? null : text(v, "basis"),
                    status, confidence, source, rationale, reason, alt));
        }
    }

    private static List<Long> roomIds(JsonNode w) {
        List<Long> ids = new ArrayList<>();
        for (String field : new String[] {"roomIds", "roomRefs"}) {
            for (JsonNode n : array(w, field)) {
                Long id = longValue(n);
                if (id != null && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /** Volume entries keyed by room id; the last entry of a room wins. */
    private static Map<Long, JsonNode> volumesByRoom(JsonNode w) {
        Map<Long, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode v : array(w, "volumes")) {
            if (v.isObject()) {
                Long id = longValue(v.get("roomId"));
                if (id != null) {
                    out.put(id, v);
                }
            }
        }
        return out;
    }

    private static String firstUnit(Map<Long, JsonNode> volumes) {
        for (JsonNode v : volumes.values()) {
            String u = text(v, "unit");
            if (u != null) {
                return u;
            }
        }
        return null;
    }

    private static List<Note> notes(JsonNode root, String field) {
        List<Note> out = new ArrayList<>();
        for (JsonNode n : array(root, field)) {
            if (n.isObject()) {
                String t = text(n, "text");
                if (t != null) {
                    out.add(new Note(t, text(n, "workCode"), longValue(n.get("roomId")), sourceText(n.get("source"))));
                }
            } else if (n.isTextual()) {
                String t = clip(n.asText());
                if (t != null) {
                    out.add(new Note(t, null, null, null));
                }
            }
        }
        return out;
    }

    // ---- tolerant JSON helpers ----

    private static List<JsonNode> array(JsonNode node, String field) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode a = node == null || !node.isObject() ? null : node.get(field);
        if (a != null && a.isArray()) {
            a.forEach(out::add);
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node == null ? null : node.get(field);
        if (n == null || n.isNull() || !n.isValueNode()) {
            return null;
        }
        return clip(n.asText());
    }

    private static String clip(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
    }

    private static String sourceText(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isObject()) {
            String file = text(n, "file");
            String page = text(n, "page");
            if (file == null) {
                return page == null ? null : "p." + page;
            }
            return page == null ? file : file + ", p." + page;
        }
        return n.isValueNode() ? clip(n.asText()) : null;
    }

    private static BigDecimal decimal(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            if (n.isNumber()) {
                double d = n.asDouble();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return null;
                }
                return n.decimalValue();
            }
            if (n.isTextual()) {
                return new BigDecimal(n.asText().trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static Long longValue(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isIntegralNumber() && n.canConvertToLong()) {
            return n.asLong();
        }
        if (n.isTextual()) {
            try {
                return Long.parseLong(n.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
