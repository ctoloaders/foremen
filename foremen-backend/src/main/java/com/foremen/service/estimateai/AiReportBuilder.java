package com.foremen.service.estimateai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.foremen.service.offer.OfferSnapshot;

/**
 * FOR-05-04 (Requirements 9.1-9.3, 8.8; design B7) — builds the {@code ai_report} JSON document of an AI
 * offer from the validated plan and the offer snapshot. Pure and static. Model text is cut to 500
 * characters; generated text is never stored (warnings carry code + params).
 */
public final class AiReportBuilder {

    static final int MAX_TEXT = 500;

    private AiReportBuilder() {
    }

    /**
     * @param plan      the validated plan (may be {@code null}: then only the summary is produced)
     * @param snapshot  the snapshot the offer is created with
     * @param roomsArea total floor area of the project's rooms, or {@code null} when unknown
     * @param extra     extra warnings produced by the pipeline (rejected edits, unsaved rooms)
     */
    public static Map<String, Object> build(EstimatePlan plan, OfferSnapshot snapshot, BigDecimal roomsArea,
                                            List<ReportWarning> extra) {
        List<ReportWarning> warnings = new ArrayList<>();
        if (plan != null) {
            warnings.addAll(plan.warnings());
        }
        if (extra != null) {
            warnings.addAll(extra);
        }
        List<Map<String, Object>> byCategory = new ArrayList<>();
        int lines = 0;
        if (snapshot != null && snapshot.groups() != null) {
            for (OfferSnapshot.Group group : snapshot.groups()) {
                if (group == null || group.rows() == null || group.rows().isEmpty()) {
                    continue;
                }
                BigDecimal net = BigDecimal.ZERO;
                for (OfferSnapshot.Row row : group.rows()) {
                    net = net.add(rowNet(row));
                }
                lines += group.rows().size();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("categoryId", group.categoryId());
                entry.put("nameRu", group.nameRu());
                entry.put("namePl", group.namePl());
                entry.put("lines", group.rows().size());
                entry.put("totalNet", net);
                byCategory.add(entry);
            }
        }
        if (lines == 0) {
            warnings.add(ReportWarning.warning(WarningCodes.EMPTY_OFFER, null, null, Map.of()));
        }
        Map<String, Object> materials = materialsSummary(snapshot, workCodes(plan), warnings);
        BigDecimal totalNet = snapshot != null && snapshot.totalNet() != null ? snapshot.totalNet() : BigDecimal.ZERO;
        Map<String, Object> summary = new LinkedHashMap<>();
        if (roomsArea != null) {
            summary.put("roomsArea", roomsArea);
        }
        summary.put("lines", lines);
        summary.put("totalNet", totalNet);
        if (roomsArea != null && roomsArea.signum() > 0) {
            summary.put("perM2", totalNet.divide(roomsArea, 2, RoundingMode.HALF_UP));
        }
        summary.put("byCategory", byCategory);
        summary.put("materials", materials);

        List<Unpriced> unpriced = plan == null ? List.of() : plan.unpriced();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("assumptions", notes(plan == null ? List.of() : plan.assumptions()));
        report.put("questions", notes(plan == null ? List.of() : plan.questions()));
        report.put("unpriced", unpricedList(unpriced));
        report.put("warnings", warningList(warnings));
        report.put("incomplete", !unpriced.isEmpty());
        report.put("summary", summary);
        report.put("changes", new ArrayList<>());
        return report;
    }

    /** Work item id to catalog code, from the plan (the snapshot rows carry no code). */
    private static Map<Long, String> workCodes(EstimatePlan plan) {
        Map<Long, String> codes = new LinkedHashMap<>();
        if (plan != null) {
            for (PlannedWork w : plan.works()) {
                if (w.workItemId() != null && w.workCode() != null) {
                    codes.putIfAbsent(w.workItemId(), w.workCode());
                }
            }
        }
        return codes;
    }

    /**
     * FOR-05-04 (Requirement 23.9): {@code materials { lines, cellsWithMaterials, overriddenLines,
     * worksWithoutNorms[] }} read from the snapshot cells, plus one {@code MATERIAL_PRICE_RANGE_MISSING}
     * warning per {@code (work, material type)} whose placeholder line has no price range. A work without
     * norms is informational only, never a warning.
     */
    private static Map<String, Object> materialsSummary(OfferSnapshot snapshot, Map<Long, String> workCodes,
                                                        List<ReportWarning> warnings) {
        int lines = 0;
        int cellsWithMaterials = 0;
        int overriddenLines = 0;
        List<String> worksWithoutNorms = new ArrayList<>();
        Set<String> warned = new HashSet<>();
        if (snapshot != null && snapshot.groups() != null) {
            for (OfferSnapshot.Group group : snapshot.groups()) {
                if (group == null || group.rows() == null) {
                    continue;
                }
                for (OfferSnapshot.Row row : group.rows()) {
                    if (row == null) {
                        continue;
                    }
                    String code = workCodes.getOrDefault(row.workItemId(), String.valueOf(row.workItemId()));
                    boolean any = false;
                    if (row.cells() != null) {
                        for (OfferSnapshot.Cell cell : row.cells()) {
                            if (cell == null || cell.materials() == null || cell.materials().isEmpty()) {
                                continue;
                            }
                            any = true;
                            cellsWithMaterials++;
                            for (OfferSnapshot.MaterialLine m : cell.materials()) {
                                lines++;
                                if (m.overridden()) {
                                    overriddenLines++;
                                }
                                boolean noRange = m.rangeMin() == null && m.rangeMax() == null
                                        && m.concreteNet() == null;
                                if (noRange && warned.add(row.workItemId() + ":" + m.branch() + ":" + m.typeId())) {
                                    Map<String, Object> params = new LinkedHashMap<>();
                                    params.put("workCode", code);
                                    params.put("materialTypeId", m.typeId());
                                    warnings.add(ReportWarning.warning(WarningCodes.MATERIAL_PRICE_RANGE_MISSING,
                                            null, workCodes.get(row.workItemId()), params));
                                }
                            }
                        }
                    }
                    if (!any && !worksWithoutNorms.contains(code)) {
                        worksWithoutNorms.add(code);
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", lines);
        out.put("cellsWithMaterials", cellsWithMaterials);
        out.put("overriddenLines", overriddenLines);
        out.put("worksWithoutNorms", worksWithoutNorms);
        return out;
    }

    private static BigDecimal rowNet(OfferSnapshot.Row row) {
        if (row == null) {
            return BigDecimal.ZERO;
        }
        if (row.unitPrice() != null && row.quantity() != null) {
            return row.unitPrice().multiply(row.quantity()).setScale(2, RoundingMode.HALF_UP);
        }
        return row.valueNet() != null ? row.valueNet() : BigDecimal.ZERO;
    }

    private static List<Map<String, Object>> notes(List<EstimatePlan.Note> notes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EstimatePlan.Note n : notes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", cut(n.text()));
            putIfNotNull(m, "workCode", n.workCode());
            putIfNotNull(m, "roomId", n.roomId());
            putIfNotNull(m, "source", n.source());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> unpricedList(List<Unpriced> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Unpriced u : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", cut(u.name()));
            putIfNotNull(m, "unit", u.unit());
            putIfNotNull(m, "estimatedVolume", u.estimatedVolume());
            m.put("reason", u.reason());
            putIfNotNull(m, "source", u.source());
            putIfNotNull(m, "workCode", u.workCode());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> warningList(List<ReportWarning> warnings) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReportWarning w : warnings) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", w.code());
            m.put("severity", w.severity().name());
            putIfNotNull(m, "roomId", w.roomId());
            putIfNotNull(m, "workCode", w.workCode());
            m.put("params", w.params());
            out.add(m);
        }
        return out;
    }

    private static void putIfNotNull(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    static String cut(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }
}
