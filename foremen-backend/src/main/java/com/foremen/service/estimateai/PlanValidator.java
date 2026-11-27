package com.foremen.service.estimateai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.foremen.service.estimateai.PlanCatalog.CatalogWork;
import com.foremen.service.estimateai.PlanCatalog.RoomFacts;

/**
 * FOR-05-04 (Requirements 5.4, 5.5) — deterministic plan validator. Adds warnings and never rejects or
 * changes a work. Pure, never throws on null or empty input.
 */
public final class PlanValidator {

    static final BigDecimal EXCEED_FACTOR = new BigDecimal("1.5");
    static final String BASIS_FLOOR = "FLOOR_AREA";
    static final String BASIS_WALL = "WALL_AREA";

    private PlanValidator() {
    }

    public static List<ReportWarning> validate(List<PlannedWork> works, List<RoomFacts> rooms,
                                               Map<String, CatalogWork> catalog) {
        List<ReportWarning> out = new ArrayList<>();
        List<PlannedWork> ws = works == null ? List.of() : works;
        List<RoomFacts> rs = rooms == null ? List.of() : rooms;
        Map<String, CatalogWork> cat = catalog == null ? Map.of() : catalog;
        Map<Long, RoomFacts> roomById = new HashMap<>();
        for (RoomFacts r : rs) {
            if (r != null && r.id() != null) {
                roomById.put(r.id(), r);
            }
        }

        Set<Long> roomsWithWorks = new HashSet<>();
        Map<String, Set<String>> groupMembers = new LinkedHashMap<>();
        Set<String> reportedGroups = new HashSet<>();

        for (PlannedWork w : ws) {
            if (w == null) {
                continue;
            }
            roomsWithWorks.add(w.roomId());
            RoomFacts room = roomById.get(w.roomId());
            CatalogWork cw = w.workCode() == null ? null : cat.get(w.workCode());

            if (cw != null && room != null && room.roomTypeId() != null && !cw.roomTypeIds().isEmpty()
                    && !cw.roomTypeIds().contains(room.roomTypeId())) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("workCode", w.workCode());
                p.put("roomId", w.roomId());
                out.add(ReportWarning.warning(WarningCodes.WORK_ROOM_TYPE_MISMATCH, w.roomId(), w.workCode(), p));
            }
            if (w.rationale() == null || w.rationale().isBlank()) {
                out.add(ReportWarning.warning(WarningCodes.RATIONALE_MISSING, w.roomId(), w.workCode(),
                        cellParams(w.workCode(), w.roomId())));
            }
            if (room != null && w.volume() != null) {
                BigDecimal limit = limitFor(w.basis(), room);
                if (limit != null && w.volume().compareTo(limit.multiply(EXCEED_FACTOR)) > 0) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("volume", w.volume());
                    p.put("roomDimension", limit);
                    p.put("basis", w.basis());
                    out.add(ReportWarning.warning(WarningCodes.VOLUME_EXCEEDS_ROOM, w.roomId(), w.workCode(), p));
                }
            }
            if (w.alternativeGroup() != null && !w.alternativeGroup().isBlank() && w.workCode() != null) {
                String key = w.roomId() + "|" + w.alternativeGroup();
                Set<String> members = groupMembers.computeIfAbsent(key, k -> new HashSet<>());
                members.add(w.workCode());
                if (members.size() > 1 && reportedGroups.add(key)) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("group", w.alternativeGroup());
                    out.add(ReportWarning.warning(WarningCodes.ALTERNATIVES_BOTH_PRESENT, w.roomId(), w.workCode(),
                            p));
                }
            }
        }
        for (RoomFacts r : rs) {
            if (r != null && !roomsWithWorks.contains(r.id())) {
                out.add(ReportWarning.info(WarningCodes.ROOM_WITHOUT_WORKS, r.id(), null, Map.of()));
            }
        }
        return out;
    }

    private static Map<String, Object> cellParams(String workCode, Long roomId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("workCode", workCode);
        p.put("roomId", roomId);
        return p;
    }

    private static BigDecimal limitFor(String basis, RoomFacts room) {
        if (basis == null) {
            return null;
        }
        BigDecimal limit = switch (basis.trim().toUpperCase(Locale.ROOT)) {
            case BASIS_FLOOR -> room.floorArea();
            case BASIS_WALL -> room.wallArea();
            default -> null;
        };
        return limit != null && limit.signum() > 0 ? limit : null;
    }
}
