package com.foremen.service.dashboard.appearance;

import com.foremen.service.dashboard.config.WidgetConfig.Visualization;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Visualization to allowed appearance-style codes (FOR-10 Requirement 5.9). The first entry of each list
 * is the fallback style when neither the Widget_Config nor the user selects one. Mirrored by the
 * frontend {@code style-catalog.ts}.
 */
public final class WidgetStyleCatalog {

    private static final Map<Visualization, List<String>> STYLES = new EnumMap<>(Visualization.class);

    static {
        STYLES.put(Visualization.TABLE, List.of("STANDARD", "COMPACT", "STRIPED"));
        STYLES.put(Visualization.LIST, List.of("STANDARD", "COMPACT", "DIVIDED"));
        STYLES.put(Visualization.BAR, List.of("SOLID", "OUTLINED", "MINIMAL"));
        STYLES.put(Visualization.LINE, List.of("SOLID", "OUTLINED", "MINIMAL"));
        STYLES.put(Visualization.AREA, List.of("SOLID", "OUTLINED", "MINIMAL"));
        STYLES.put(Visualization.PIE, List.of("STANDARD", "THIN_RING", "WITH_LABELS"));
        STYLES.put(Visualization.DONUT, List.of("STANDARD", "THIN_RING", "WITH_LABELS"));
        STYLES.put(Visualization.KPI, List.of("PLAIN", "CARD", "WITH_TREND"));
        STYLES.put(Visualization.PROJECT_CARD, List.of("DETAILED", "COMPACT", "MINIMAL"));
    }

    private WidgetStyleCatalog() {
    }

    /** Allowed style codes for the visualization, in catalog order (never empty). */
    public static List<String> stylesFor(Visualization visualization) {
        return STYLES.get(visualization);
    }

    /** The fallback style: first catalog entry. */
    public static String defaultStyle(Visualization visualization) {
        return STYLES.get(visualization).get(0);
    }

    public static boolean isAllowed(Visualization visualization, String style) {
        return visualization != null && style != null && STYLES.get(visualization).contains(style);
    }

    /** The full catalog, for the catalog/appearance endpoints. */
    public static Map<Visualization, List<String>> all() {
        return Map.copyOf(STYLES);
    }
}
