package com.foremen.service.dashboard.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.foremen.service.dashboard.config.WidgetConfig.Localized;

/**
 * A DRAFT widget produced by the model. The config is kept as a raw document: it has already been
 * checked by the client with the config validator, and the service re-validates it before returning.
 *
 * @param names        widget names (ru / pl)
 * @param descriptions widget descriptions (ru / pl)
 * @param colSpan      grid columns, 1..4
 * @param rowSpan      grid rows, 1..9
 * @param projectScope {@code NONE} or {@code SINGLE_PROJECT}
 * @param config       the Widget_Config document
 * @param explanation  short explanation of the choices (ru / pl)
 */
public record DraftResponse(Localized names, Localized descriptions, int colSpan, int rowSpan,
                            String projectScope, JsonNode config, Localized explanation) {

    public static final String SCOPE_NONE = "NONE";
    public static final String SCOPE_SINGLE_PROJECT = "SINGLE_PROJECT";

    public boolean singleProject() {
        return SCOPE_SINGLE_PROJECT.equals(projectScope);
    }
}
