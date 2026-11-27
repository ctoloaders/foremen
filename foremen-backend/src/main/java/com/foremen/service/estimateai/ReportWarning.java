package com.foremen.service.estimateai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FOR-05-04 — one deterministic warning of the AI report. Text is never stored: the frontend
 * translates {@code code} and renders {@code params}.
 */
public record ReportWarning(String code, Severity severity, Long roomId, String workCode,
                            Map<String, Object> params) {

    public enum Severity { INFO, WARNING }

    public ReportWarning {
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    public static ReportWarning warning(String code, Long roomId, String workCode, Map<String, Object> params) {
        return new ReportWarning(code, Severity.WARNING, roomId, workCode, params);
    }

    public static ReportWarning info(String code, Long roomId, String workCode, Map<String, Object> params) {
        return new ReportWarning(code, Severity.INFO, roomId, workCode, params);
    }
}
