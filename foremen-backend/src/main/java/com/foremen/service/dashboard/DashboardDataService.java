package com.foremen.service.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.UserDao;
import com.foremen.dao.UserDashboardWidgetDao;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.UserDashboardWidgetEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.dashboard.config.WidgetConfig;
import com.foremen.service.dashboard.config.WidgetConfigValidator;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.DatasetRegistry;
import com.foremen.service.dashboard.query.DashboardQueryEngine;
import com.foremen.service.dashboard.query.DashboardQueryTimeoutException;
import com.foremen.service.dashboard.query.QueryContext;
import com.foremen.service.dashboard.query.WidgetResult;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Secured widget data fetch (FOR-10 Requirement 6.3). Ordered pipeline:
 * <ol>
 *   <li>load the placement and verify ownership (404 missing / 403 {@code error.dashboard.forbidden});</li>
 *   <li>verify the widget is active;</li>
 *   <li>verify Has_Access on every Required_Resource from the caller's current role permissions, plus
 *       the bound project for a Project_Widget (same {@link DashboardAccessService#evaluate} as the layout
 *       read, so list and fetch never disagree — R9.5);</li>
 *   <li>apply project and row scoping ({@link QueryContext});</li>
 *   <li>apply filters, date range, grouping, aggregation, sort and limit ({@link DashboardQueryEngine});</li>
 *   <li>return the normalized {@link WidgetResult}.</li>
 * </ol>
 * A failure at (2)/(3) is 403 {@code error.dashboard.widget.disabled} with a {@code reason} param
 * ({@code INACTIVE}, {@code NO_ACCESS}, {@code PROJECT_UNAVAILABLE}) and never executes the query.
 * Date ranges are validated first (400 {@code error.dashboard.date.invalid}). A statement timeout
 * returns an empty, {@code truncated} result (R6.5) instead of a server error.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardDataService {

    public static final int MAX_RANGE_YEARS = 5;

    private final UserDashboardWidgetDao placementDao;
    private final UserDao userDao;
    private final DashboardAccessService accessService;
    private final ProjectAccessCache projectAccessCache;
    private final DatasetRegistry datasetRegistry;
    private final WidgetConfigValidator configValidator;
    private final DashboardQueryEngine queryEngine;

    /** Jackson 2 mapper matching {@link WidgetConfigValidator}'s JsonNode model (no such bean in Boot 4). */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Transactional(readOnly = true)
    public WidgetResult fetch(Long userId, Long placementId, LocalDate from, LocalDate to) {
        // (1) placement + ownership
        UserDashboardWidgetEntity placement = placementDao.findById(placementId).orElseThrow(() ->
                new ForemenApiException(HttpStatus.NOT_FOUND, "error.dashboard.placement.notFound"));
        if (!placement.getUser().getId().equals(userId)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.dashboard.forbidden");
        }
        validateRange(from, to);
        UserEntity user = loadUser(userId);
        String role = user.getRole().getCode();
        DashboardWidgetEntity widget = placement.getWidget();
        boolean projectWidget = DashboardLayoutService.PROJECT_SCOPE_SINGLE.equals(widget.getProjectScope());
        // R6.7: the project always comes from the stored placement, never from a request parameter
        Long boundProject = placement.getProject() == null ? null : placement.getProject().getId();
        Dataset dataset = resolveDataset(widget.getConfig());
        // (2) active + (3) Has_Access + project availability
        WidgetState state = accessService.evaluate(userId, role, Boolean.TRUE.equals(widget.getActive()),
                Set.copyOf(dataset.requiredResources()), projectWidget, boundProject);
        if (state != WidgetState.AVAILABLE) {
            throw disabled(state.name());
        }
        WidgetConfig cfg = parse(widget.getConfig(), projectWidget);
        // (4) scoping, (5) query, (6) result
        return execute(dataset, cfg, queryContext(userId, user, role, from, to, boundProject));
    }

    /**
     * Admin builder preview: the same pipeline without a placement, executed with the caller's own
     * {@link QueryContext} (an ADMIN passes Has_Access and skips project scope).
     */
    @Transactional(readOnly = true)
    public WidgetResult previewFetch(Long userId, Map<String, Object> config, boolean singleProject,
                                     Long projectId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        UserEntity user = loadUser(userId);
        String role = user.getRole().getCode();
        Dataset dataset = resolveDataset(config);
        Long bound = singleProject ? projectId : null;
        WidgetState state = accessService.evaluate(userId, role, true, Set.copyOf(dataset.requiredResources()),
                singleProject, bound);
        if (state != WidgetState.AVAILABLE) {
            throw disabled(state.name());
        }
        WidgetConfig cfg = parse(config, singleProject);
        return execute(dataset, cfg, queryContext(userId, user, role, from, to, bound));
    }

    /**
     * Global date filter validation (R2.5): both bounds absent (widget not filtered) or both present
     * with {@code from <= to} and a span of at most {@value #MAX_RANGE_YEARS} years.
     */
    public static void validateRange(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return;
        }
        if (from == null || to == null || from.isAfter(to) || from.plusYears(MAX_RANGE_YEARS).isBefore(to)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.dashboard.date.invalid");
        }
    }

    private WidgetResult execute(Dataset dataset, WidgetConfig cfg, QueryContext ctx) {
        try {
            return queryEngine.execute(dataset, cfg, ctx);
        } catch (DashboardQueryTimeoutException e) {
            log.warn("Dashboard query timed out: dataset={}", dataset.code());
            return new WidgetResult(List.of(), List.of(), null, null, true, Instant.now());
        }
    }

    private QueryContext queryContext(Long userId, UserEntity user, String role, LocalDate from, LocalDate to,
                                      Long boundProject) {
        boolean admin = ForemenPermissionEvaluator.ADMIN_ROLE_CODE.equals(role);
        Set<Long> allowed = admin ? Set.of() : projectAccessCache.get(userId);
        // defence in depth (R12.4): the bound project must still be accessible at fetch time
        if (boundProject != null && !admin && !allowed.contains(boundProject)) {
            throw disabled(WidgetState.PROJECT_UNAVAILABLE.name());
        }
        String locale = user.getLocale() == null ? "ru" : user.getLocale().getLanguage();
        return new QueryContext(userId, role, allowed, admin, from, to, boundProject, locale);
    }

    private Dataset resolveDataset(Map<String, Object> config) {
        Object code = config == null ? null : config.get("dataset");
        if (code == null) {
            throw invalidConfig();
        }
        return datasetRegistry.get(code.toString()).orElseThrow(DashboardDataService::invalidConfig);
    }

    private WidgetConfig parse(Map<String, Object> config, boolean singleProject) {
        JsonNode node = JSON.valueToTree(config);
        WidgetConfigValidator.Result result = configValidator.validate(node, singleProject);
        if (!result.valid() || result.config() == null) {
            log.warn("Stored widget config is invalid: {}", result.violations());
            throw invalidConfig().withParams(Map.of("violations", result.violations()));
        }
        return result.config();
    }

    private static ForemenApiException invalidConfig() {
        return new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, "error.dashboard.config.invalid");
    }

    private UserEntity loadUser(Long userId) {
        return userDao.findById(userId).orElseThrow(() ->
                new ForemenApiException(HttpStatus.NOT_FOUND, "error.user.notFound"));
    }

    private static ForemenApiException disabled(String reason) {
        return new ForemenApiException(HttpStatus.FORBIDDEN, "error.dashboard.widget.disabled")
                .withParams(Map.of("reason", reason));
    }
}
