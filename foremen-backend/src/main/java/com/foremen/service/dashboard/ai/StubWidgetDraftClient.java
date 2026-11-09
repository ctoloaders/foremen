package com.foremen.service.dashboard.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.service.dashboard.ai.WidgetDraftException.Reason;
import com.foremen.service.dashboard.config.WidgetConfig;
import com.foremen.service.dashboard.config.WidgetConfig.Localized;
import com.foremen.service.dashboard.dataset.DatasetCatalogDto;
import com.foremen.service.dashboard.dataset.DatasetCatalogDto.FieldDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * FOR-10 (Requirement 13.3) — deterministic {@link WidgetDraftClient} for tests and the Docker stack,
 * active when {@code foremen.dashboard.ai.enabled=true} and {@code foremen.dashboard.ai.llm.stub=true}.
 *
 * <p>Always answers with a KPI widget (COUNT) over the first catalog dataset that has an aggregatable
 * field, so the draft is valid against the real catalog. The marker {@code STUB:FAIL} in the prompt
 * simulates a provider outage ({@link Reason#LLM_UNAVAILABLE}).
 */
@Component
@ConditionalOnExpression("${foremen.dashboard.ai.enabled:false} and ${foremen.dashboard.ai.llm.stub:false}")
public class StubWidgetDraftClient implements WidgetDraftClient {

    /** Prompt marker that makes the stub fail like an unavailable provider. */
    public static final String FAIL_MARKER = "STUB:FAIL";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public DraftResponse draft(DraftRequest request) {
        if (request.prompt() != null && request.prompt().contains(FAIL_MARKER)) {
            throw new WidgetDraftException(Reason.LLM_UNAVAILABLE, "stub: LLM is down");
        }
        for (DatasetCatalogDto d : request.catalog()) {
            Optional<FieldDto> field = d.fields().stream().filter(FieldDto::aggregatable).findFirst();
            if (field.isPresent()) {
                return new DraftResponse(
                        new Localized("Количество (черновик ИИ)", "Liczba (szkic AI)"),
                        new Localized("Черновик виджета, подготовленный ассистентом.",
                                "Szkic widżetu przygotowany przez asystenta."),
                        1, 1, DraftResponse.SCOPE_NONE, config(d.code(), field.get().name()),
                        new Localized("Простой KPI-счётчик записей набора данных.",
                                "Prosty wskaźnik KPI liczby rekordów zbioru danych."));
            }
        }
        throw new WidgetDraftException(Reason.INVALID_RESPONSE, "stub: no dataset with an aggregatable field");
    }

    private JsonNode config(String dataset, String field) {
        ObjectNode cfg = mapper.createObjectNode();
        cfg.put("schemaVersion", WidgetConfig.SCHEMA_VERSION);
        cfg.put("dataset", dataset);
        cfg.put("visualization", "KPI");
        cfg.set("title", localized("Количество", "Liczba"));
        ObjectNode measure = cfg.putArray("measures").addObject();
        measure.put("field", field);
        measure.put("agg", "COUNT");
        measure.put("alias", "total");
        measure.set("label", localized("Количество", "Liczba"));
        return cfg;
    }

    private ObjectNode localized(String ru, String pl) {
        ObjectNode n = mapper.createObjectNode();
        n.put("ru", ru);
        n.put("pl", pl);
        return n;
    }
}
