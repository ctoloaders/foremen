package com.foremen.service.dashboard.dataset;

import java.util.Map;
import java.util.Set;

/**
 * Resources that intentionally have no dashboard dataset (FOR-10 Requirement 6.2a / 6.2e), each with a
 * one-line justification. {@code DatasetCoverageTest} fails when a seeded resource code is neither
 * required by a registered {@link Dataset} nor listed here.
 */
public final class DatasetCoverage {

    private static final String REFERENCE = "reference data (dictionary): joined only as label fields";
    private static final String PERMISSION_CATALOG = "technical permission catalog, no business data";

    /** Resource code to justification. */
    public static final Map<String, String> EXCLUDED = Map.ofEntries(
            Map.entry("MEASUREMENT_UNITS", REFERENCE),
            Map.entry("CURRENCIES", REFERENCE),
            Map.entry("VAT_RATES", REFERENCE),
            Map.entry("ROOM_TYPES", REFERENCE),
            Map.entry("WORKER_TYPES", REFERENCE),
            Map.entry("WORK_CATEGORIES", REFERENCE),
            Map.entry("DELIVERY_CATEGORIES", REFERENCE),
            Map.entry("DELIVERY_STATUSES", REFERENCE),
            Map.entry("UNPLANNED_EXPENSE_CATEGORIES", REFERENCE),
            Map.entry("MATERIAL_CATEGORIES", REFERENCE),
            Map.entry("MATERIAL_TYPES", REFERENCE),
            Map.entry("MATERIALS", REFERENCE),
            Map.entry("MATERIAL_PRODUCERS", REFERENCE),
            Map.entry("MATERIAL_SELLERS", REFERENCE),
            Map.entry("CONSTRUCTION_MATERIAL_TYPES", REFERENCE),
            Map.entry("OFFER_PACKAGES", REFERENCE),
            Map.entry("PACKAGE_ASSORTMENT", REFERENCE),
            Map.entry("RESOURCES", PERMISSION_CATALOG),
            Map.entry("OPERATIONS", PERMISSION_CATALOG),
            Map.entry("EXPENSE_UPLOAD", "action-only permission (upload), no own data"),
            Map.entry("DASHBOARD_WIDGETS", "widget definitions are dashboard configuration, not business data"));

    public static Set<String> excludedCodes() {
        return EXCLUDED.keySet();
    }

    private DatasetCoverage() {
    }
}
