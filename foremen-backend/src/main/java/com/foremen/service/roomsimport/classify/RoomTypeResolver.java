package com.foremen.service.roomsimport.classify;

import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps model output to a room type id (Requirement 4). The model-supplied id is validated against
 * the catalogue, never trusted blindly; otherwise the {@code typeHint} and then the {@code label}
 * are matched case-insensitively against the RU/PL/code names. Unresolved input yields a
 * {@code null} id plus {@code ROOM_TYPE_UNRESOLVED} carrying up to three candidates.
 */
public final class RoomTypeResolver {

    static final int MAX_CANDIDATES = 3;
    static final double MIN_CANDIDATE_SCORE = 0.4;

    /** One catalogue entry visible to the project. */
    public record RoomTypeOption(Long id, String code, String nameRu, String namePl) {}

    /** Resolution outcome: {@code roomTypeId} is null exactly when {@code warning} is present. */
    public record Resolution(Long roomTypeId, Optional<Warning> warning) {}

    private final List<RoomTypeOption> catalogue;

    public RoomTypeResolver(Collection<RoomTypeOption> catalogue) {
        this.catalogue = catalogue == null ? List.of() : List.copyOf(catalogue);
    }

    /**
     * @param modelRoomTypeId id returned by the model (may be null / unknown)
     * @param typeHint        type name returned by the model (may be null)
     * @param label           room label from the source (may be null)
     * @param roomTempId      temp id of the draft room, used for the warning
     */
    public Resolution resolve(Long modelRoomTypeId, String typeHint, String label, String roomTempId) {
        if (modelRoomTypeId != null) {
            for (RoomTypeOption o : catalogue) {
                if (modelRoomTypeId.equals(o.id())) {
                    return new Resolution(o.id(), Optional.empty());
                }
            }
        }
        for (String text : new String[] {typeHint, label}) {
            Optional<RoomTypeOption> match = matchByName(text);
            if (match.isPresent()) {
                return new Resolution(match.get().id(), Optional.empty());
            }
        }
        return new Resolution(null, Optional.of(unresolved(typeHint, label, roomTempId)));
    }

    private Optional<RoomTypeOption> matchByName(String text) {
        String n = normalize(text);
        if (n.isEmpty()) {
            return Optional.empty();
        }
        for (RoomTypeOption o : catalogue) {
            if (n.equals(normalize(o.code())) || n.equals(normalize(o.nameRu())) || n.equals(normalize(o.namePl()))) {
                return Optional.of(o);
            }
        }
        return Optional.empty();
    }

    private Warning unresolved(String typeHint, String label, String roomTempId) {
        String query = normalize(typeHint).isEmpty() ? normalize(label) : normalize(typeHint);
        List<Map<String, Object>> candidates = new ArrayList<>();
        catalogue.stream()
                .map(o -> Map.entry(o, score(query, o)))
                .filter(e -> e.getValue() >= MIN_CANDIDATE_SCORE)
                .sorted(Map.Entry.<RoomTypeOption, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(e -> e.getKey().id(), Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(MAX_CANDIDATES)
                .forEach(e -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("roomTypeId", e.getKey().id());
                    c.put("code", e.getKey().code());
                    c.put("nameRu", e.getKey().nameRu());
                    c.put("namePl", e.getKey().namePl());
                    candidates.add(c);
                });
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("typeHint", typeHint);
        params.put("label", label);
        return new Warning(WarningCodes.ROOM_TYPE_UNRESOLVED, Severity.WARNING, roomTempId, "roomTypeId",
                params, Map.of("candidates", List.copyOf(candidates)));
    }

    /** Best similarity of the query to any of the entry's names, in [0,1]. */
    private static double score(String query, RoomTypeOption o) {
        if (query.isEmpty()) {
            return 0;
        }
        double best = 0;
        for (String name : new String[] {o.code(), o.nameRu(), o.namePl()}) {
            String n = normalize(name);
            if (n.isEmpty()) {
                continue;
            }
            double s = similarity(query, n);
            if (query.contains(n) || n.contains(query)) {
                s = Math.max(s, 0.8);
            }
            best = Math.max(best, s);
        }
        return best;
    }

    private static double similarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        return max == 0 ? 1 : 1.0 - (double) levenshtein(a, b) / max;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** Lower-case, strip diacritics (incl. Polish ł), turn punctuation/underscores into single spaces. */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.toLowerCase(Locale.ROOT).replace('ł', 'l').replace('ё', 'е');
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        t = t.replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim();
        return t;
    }
}
