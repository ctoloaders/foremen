package com.foremen.service.dashboard;

import java.util.List;

/** A user's placement as returned by {@code GET /api/dashboard} (FOR-10 design "REST API"). */
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
        List<String> availableStyles) {

    /** Copy with the effective (user-overridden) style. */
    public PlacementView withStyle(String effective) {
        return new PlacementView(id, widgetId, code, nameRu, namePl, cellIndex, colSpan, rowSpan, visualization,
                projectId, projectName, dateAware, state, missingResources, effective, defaultStyle,
                availableStyles);
    }
}
