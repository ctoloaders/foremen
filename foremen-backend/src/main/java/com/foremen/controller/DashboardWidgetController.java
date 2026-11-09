package com.foremen.controller;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.dashboard.DashboardDataService;
import com.foremen.service.dashboard.DashboardLayoutService;
import com.foremen.service.dashboard.DashboardWidgetService;
import com.foremen.service.dashboard.DashboardWidgetService.AdminWidgetView;
import com.foremen.service.dashboard.DashboardWidgetService.WidgetCommand;
import com.foremen.service.dashboard.ai.DashboardAiService;
import com.foremen.service.dashboard.config.WidgetConfigValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.foremen.service.dashboard.dataset.DatasetCatalogDto;
import com.foremen.service.dashboard.dataset.DatasetRegistry;
import com.foremen.service.dashboard.query.WidgetResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Admin management of the dashboard widget library (FOR-10 design "REST API", admin part).
 * Guarded by {@code DASHBOARD_WIDGETS}: every handler carries a {@link PermissionOperation}
 * (class-level {@link PermissionResource}), so {@code PermissionAnnotationValidator} sees a complete
 * declaration. The AI draft endpoint is added by FOR-10 task 5.3.
 */
@RestController
@RequestMapping("/api/dashboard-widgets")
@RequiredArgsConstructor
@PermissionResource("DASHBOARD_WIDGETS")
public class DashboardWidgetController {

    private final DashboardWidgetService widgetService;
    private final DashboardDataService dataService;
    private final DatasetRegistry datasetRegistry;
    private final DashboardAiService aiService;

    /** Create/update body (frontend {@code AdminWidgetRequest}). {@code code} is ignored on update. */
    public record WidgetRequest(String code, String nameRu, String namePl, String descriptionRu,
                                String descriptionPl, Map<String, Object> config, Integer colSpan,
                                Integer rowSpan, String projectScope) {
        WidgetCommand toCommand() {
            return new WidgetCommand(code, nameRu, namePl, descriptionRu, descriptionPl, config, colSpan,
                    rowSpan, projectScope);
        }
    }

    /** Body of {@code /validate} and {@code /preview} (frontend {@code WidgetPreviewBody}). */
    public record DraftRequest(Map<String, Object> config, String projectScope, Long projectId,
                               LocalDate dateFrom, LocalDate dateTo) {
    }

    public record ValidateResponse(boolean valid, List<WidgetConfigValidator.Violation> violations) {
    }

    @GetMapping
    @PermissionOperation("READ")
    public Page<AdminWidgetView> list(Pageable pageable,
                                      @RequestParam(name = "query", required = false) String query,
                                      @RequestParam(name = "active", required = false) Boolean active) {
        return widgetService.list(pageable, query, active);
    }

    @GetMapping("/datasets")
    @PermissionOperation("READ")
    public List<DatasetCatalogDto> datasets() {
        return datasetRegistry.catalog();
    }

    @GetMapping("/{id}")
    @PermissionOperation("READ")
    public AdminWidgetView get(@PathVariable Long id) {
        return widgetService.get(id);
    }

    @PostMapping
    @PermissionOperation("CREATE")
    public ResponseEntity<AdminWidgetView> create(@RequestBody WidgetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(widgetService.create(request.toCommand()));
    }

    @PutMapping("/{id}")
    @PermissionOperation("UPDATE")
    public AdminWidgetView update(@PathVariable Long id, @RequestBody WidgetRequest request) {
        return widgetService.update(id, request.toCommand());
    }

    @PostMapping("/{id}/activate")
    @PermissionOperation("UPDATE")
    public AdminWidgetView activate(@PathVariable Long id) {
        return widgetService.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    @PermissionOperation("UPDATE")
    public AdminWidgetView deactivate(@PathVariable Long id) {
        return widgetService.deactivate(id);
    }

    @DeleteMapping("/{id}")
    @PermissionOperation("DELETE")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        widgetService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/validate")
    @PermissionOperation("READ")
    public ValidateResponse validate(@RequestBody DraftRequest request) {
        List<WidgetConfigValidator.Violation> violations =
                widgetService.validate(request.config(), isSingleProject(request.projectScope()));
        return new ValidateResponse(violations.isEmpty(), violations);
    }

    @PostMapping("/preview")
    @PermissionOperation("READ")
    public WidgetResult preview(@RequestBody DraftRequest request) {
        boolean single = isSingleProject(request.projectScope());
        List<WidgetConfigValidator.Violation> violations = widgetService.validate(request.config(), single);
        if (!violations.isEmpty()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.dashboard.config.invalid")
                    .withParams(Map.of("violations", violations));
        }
        return dataService.previewFetch(actingUserId(), request.config(), single, request.projectId(),
                request.dateFrom(), request.dateTo());
    }

    /** Body of {@code /ai-draft}. */
    public record AiDraftBody(@NotBlank @Size(max = DashboardAiService.MAX_PROMPT_LENGTH) String prompt,
                              String locale) {
    }

    /** Returns a DRAFT only; nothing is persisted (Requirement 13). */
    @PostMapping("/ai-draft")
    @PermissionOperation("CREATE")
    public DashboardAiService.AiDraftResponse aiDraft(@Valid @RequestBody AiDraftBody body) {
        return aiService.draft(actingUserId(), body.prompt(), body.locale());
    }

    private static boolean isSingleProject(String projectScope) {
        return DashboardLayoutService.PROJECT_SCOPE_SINGLE.equals(projectScope);
    }

    private Long actingUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || auth.getName().isBlank()) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized");
        }
        try {
            return Long.valueOf(auth.getName().trim());
        } catch (NumberFormatException ex) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized");
        }
    }
}
