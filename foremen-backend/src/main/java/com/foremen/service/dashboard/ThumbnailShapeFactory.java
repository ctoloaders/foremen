package com.foremen.service.dashboard;

import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.service.dashboard.appearance.DashboardAppearanceService;
import com.foremen.service.dashboard.config.WidgetConfig.Visualization;

import java.util.Collection;
import java.util.Map;

/**
 * Builds a {@link ThumbnailShape} from the stored widget config only (FOR-10 R22.1, R22.2). No dataset
 * lookup and no access evaluation, so the result is identical for accessible and inaccessible callers.
 * Malformed or missing config yields zeros/false (and the visualization when known).
 */
public final class ThumbnailShapeFactory {

    private ThumbnailShapeFactory() {
    }

    public static ThumbnailShape from(DashboardWidgetEntity widget) {
        Map<String, Object> cfg = widget == null ? null : widget.getConfig();
        if (cfg == null) {
            return new ThumbnailShape(null, null, 0, 0, 0, false, false, false);
        }
        Object vis = cfg.get("visualization");
        String visualization = vis == null ? null : vis.toString();
        Visualization viz = DashboardAppearanceService.parse(visualization);

        Map<?, ?> presentation = cfg.get("presentation") instanceof Map<?, ?> m ? m : Map.of();
        Object cfgStyle = presentation.get("style");
        String style = DashboardAppearanceService.effectiveStyle(viz, cfgStyle == null ? null : cfgStyle.toString(), null);

        Collection<?> dims = cfg.get("dimensions") instanceof Collection<?> c ? c : java.util.List.of();
        boolean temporal = dims.stream().anyMatch(d -> d instanceof Map<?, ?> dm && dm.get("bucket") != null);

        return new ThumbnailShape(visualization, style, size(cfg.get("columns")), dims.size(),
                size(cfg.get("measures")), temporal, Boolean.TRUE.equals(presentation.get("stacked")),
                Boolean.TRUE.equals(presentation.get("compareToPrevious")));
    }

    private static int size(Object o) {
        return o instanceof Collection<?> c ? c.size() : 0;
    }
}
