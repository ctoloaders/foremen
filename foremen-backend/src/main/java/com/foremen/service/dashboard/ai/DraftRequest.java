package com.foremen.service.dashboard.ai;

import com.foremen.service.dashboard.dataset.DatasetCatalogDto;

import java.util.List;

/**
 * Input of {@link WidgetDraftClient#draft} (FOR-10 Requirement 13.5): the administrator's text, the UI
 * language hint, the dataset catalog (names, types, capabilities only) and the route whitelist.
 * It never carries business or user data.
 *
 * @param prompt  free-text request of the administrator
 * @param locale  optional UI language ({@code ru} / {@code pl}), may be {@code null}
 * @param catalog datasets visible to the administrator
 * @param routes  whitelisted link route templates
 */
public record DraftRequest(String prompt, String locale, List<DatasetCatalogDto> catalog, List<String> routes) {

    public DraftRequest {
        catalog = catalog == null ? List.of() : List.copyOf(catalog);
        routes = routes == null ? List.of() : List.copyOf(routes);
    }
}
