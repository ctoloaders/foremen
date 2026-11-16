package com.foremen.service.dashboard;

import java.util.List;
import java.util.Map;

/**
 * A user's placement as returned by {@code GET /api/dashboard} (FOR-10 design "REST API").
 *
 * <p>{@code render} is the render-only projection of the widget config (title, column formats, links,
 * presentation). It is {@code null} unless the placement is AVAILABLE and never carries dataset, filters,
 * sort or measures (R8.8, R22.2).
 */
public record PlacementView(
        Long id,
        Long widgetId,
        String code,
        String nameRu,
        String namePl,
        int cellIndex,
        int colSpan,
        int rowSpan,
        String visualization,
        Long projectId,
        String projectName,
        boolean dateAware,
        WidgetState state,
        List<String> missingResources,
        String style,
        String defaultStyle,
        List<String> availableStyles,
        Map<String, Object> render) {

    /** Copy with the effective (user-overridden) style. */
    public PlacementView withStyle(String effective) {
        return new PlacementView(id, widgetId, code, nameRu, namePl, cellIndex, colSpan, rowSpan, visualization,
                projectId, projectName, dateAware, state, missingResources, effective, defaultStyle,
                availableStyles, render);
    }
}
