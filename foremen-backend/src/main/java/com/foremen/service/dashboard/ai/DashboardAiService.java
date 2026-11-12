package com.foremen.service.dashboard.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.dashboard.DashboardAiProperties;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.dashboard.DashboardAccessService;
import com.foremen.service.dashboard.DashboardNotificationEmitter;
import com.foremen.service.dashboard.ai.WidgetDraftException.Reason;
import com.foremen.service.dashboard.config.RouteWhitelist;
import com.foremen.service.dashboard.config.WidgetConfig.Localized;
import com.foremen.service.dashboard.config.WidgetConfigValidator;
import com.foremen.service.dashboard.config.WidgetConfigValidator.Violation;
import com.foremen.service.dashboard.dataset.DatasetCatalogDto;
import com.foremen.service.dashboard.dataset.DatasetRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FOR-10 (Requirement 13) — AI widget draft orchestration.
 *
 * <p>Rate limit (per administrator, sliding minute window), catalog + route whitelist as model input,
 * re-validation of the returned config, advisory warnings, and failure mapping:
 * {@code DISABLED -> 503 error.dashboard.ai.disabled}, everything else {@code -> 502
 * error.dashboard.ai.failed} plus a best-effort admin notification. The draft is NEVER saved here and
 * the prompt text is never logged.
 */
@Slf4j
@Service
public class DashboardAiService {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String ERR_DISABLED = "error.dashboard.ai.disabled";
    public static final String ERR_FAILED = "error.dashboard.ai.failed";
    public static final String ERR_RATE_LIMITED = "error.dashboard.ai.rate.limited";
    public static final int MAX_PROMPT_LENGTH = 2000;

    private static final long WINDOW_MS = 60_000L;

    /** Response of {@code POST /api/dashboard-widgets/ai-draft} (frontend {@code AiDraftResponse}). */
    public record AiDraftResponse(String nameRu, String namePl, String descriptionRu, String descriptionPl,
                                  int colSpan, int rowSpan, String projectScope, Map<String, Object> config,
                                  Localized explanation, List<Violation> warnings) {
    }

    private final WidgetDraftClient client;
    private final DatasetRegistry datasetRegistry;
    private final WidgetConfigValidator validator;
    private final DashboardNotificationEmitter notifications;
    private final DashboardAccessService accessService;
    private final RoleDao roleDao;
    private final DashboardAiProperties properties;
    private final Clock clock;
    private final Map<Long, Deque<Long>> calls = new ConcurrentHashMap<>();

    @Autowired
    public DashboardAiService(WidgetDraftClient client, DatasetRegistry datasetRegistry,
                              WidgetConfigValidator validator, DashboardNotificationEmitter notifications,
                              DashboardAccessService accessService, RoleDao roleDao,
                              DashboardAiProperties properties) {
        this(client, datasetRegistry, validator, notifications, accessService, roleDao, properties,
                Clock.systemUTC());
    }

    DashboardAiService(WidgetDraftClient client, DatasetRegistry datasetRegistry,
                       WidgetConfigValidator validator, DashboardNotificationEmitter notifications,
                       DashboardAccessService accessService, RoleDao roleDao,
                       DashboardAiProperties properties, Clock clock) {
        this.client = client;
        this.datasetRegistry = datasetRegistry;
        this.validator = validator;
        this.notifications = notifications;
        this.accessService = accessService;
        this.roleDao = roleDao;
        this.properties = properties;
        this.clock = clock;
    }

    public AiDraftResponse draft(Long adminUserId, String prompt, String locale) {
        if (!client.isEnabled()) {
            throw new ForemenApiException(HttpStatus.SERVICE_UNAVAILABLE, ERR_DISABLED);
        }
        checkRateLimit(adminUserId);

        DraftRequest request = new DraftRequest(prompt, locale, datasetRegistry.catalog(),
                new TreeSet<>(RouteWhitelist.ROUTES).stream().toList());
        DraftResponse draft;
        try {
            draft = client.draft(request);
            if (draft == null || draft.config() == null) {
                throw new WidgetDraftException(Reason.INVALID_RESPONSE, "empty draft");
            }
        } catch (WidgetDraftException e) {
            throw map(adminUserId, e);
        } catch (RuntimeException e) {
            log.warn("Dashboard AI draft failed unexpectedly: {}", e.getClass().getSimpleName());
            throw failed(adminUserId, Reason.LLM_UNAVAILABLE);
        }

        List<Violation> violations = validator.validate(draft.config(), draft.singleProject()).violations();
        if (!violations.isEmpty()) {
            log.warn("Dashboard AI draft rejected by validator: {} violation(s)", violations.size());
            throw failed(adminUserId, Reason.INVALID_RESPONSE);
        }
        return new AiDraftResponse(
                text(draft.names(), true), text(draft.names(), false),
                text(draft.descriptions(), true), text(draft.descriptions(), false),
                draft.colSpan(), draft.rowSpan(), draft.projectScope(), toMap(draft.config()),
                draft.explanation(), warnings(draft.config()));
    }

    /** Plain map so the response serializes the same under Jackson 2 or 3 (the HTTP layer is Jackson 3). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        return node == null ? null : JSON.convertValue(node, Map.class);
    }

    private ForemenApiException map(Long adminUserId, WidgetDraftException e) {
        if (e.getReason() == Reason.DISABLED) {
            return new ForemenApiException(HttpStatus.SERVICE_UNAVAILABLE, ERR_DISABLED);
        }
        log.warn("Dashboard AI draft failed: reason={}", e.getReason());
        return failed(adminUserId, e.getReason());
    }

    private ForemenApiException failed(Long adminUserId, Reason reason) {
        notifications.emitAiFailed(adminUserId, reason.name());
        return new ForemenApiException(HttpStatus.BAD_GATEWAY, ERR_FAILED);
    }

    private void checkRateLimit(Long adminUserId) {
        long now = clock.millis();
        Deque<Long> window = calls.computeIfAbsent(adminUserId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= properties.rateLimitPerMinute()) {
                throw new ForemenApiException(HttpStatus.TOO_MANY_REQUESTS, ERR_RATE_LIMITED);
            }
            window.addLast(now);
        }
    }

    /** Advisory: roles that lack the datasets' required resources will not see the widget. */
    private List<Violation> warnings(JsonNode config) {
        List<Violation> out = new ArrayList<>();
        JsonNode ds = config.get("dataset");
        if (ds == null || !ds.isTextual()) {
            return out;
        }
        Set<String> required = datasetRegistry.catalog().stream()
                .filter(d -> d.code().equals(ds.asText()))
                .map(DatasetCatalogDto::requiredResources)
                .findFirst().map(l -> (Set<String>) new HashSet<>(l)).orElse(Set.of());
        if (required.isEmpty()) {
            return out;
        }
        try {
            List<String> hidden = new ArrayList<>();
            for (RoleEntity role : roleDao.findAll(Pageable.unpaged()).getContent()) {
                if (!accessService.missingResources(role.getCode(), required).isEmpty()) {
                    hidden.add(role.getCode());
                }
            }
            if (!hidden.isEmpty()) {
                hidden.sort(String::compareTo);
                out.add(new Violation("dataset", "widget.rolesWithoutAccess",
                        "Roles without access to the required resources " + new TreeSet<>(required)
                                + " will not see this widget: " + String.join(", ", hidden)));
            }
        } catch (RuntimeException e) {
            log.debug("Dashboard AI draft: role warning skipped ({})", e.getClass().getSimpleName());
        }
        return out;
    }

    private static String text(Localized l, boolean ru) {
        if (l == null) {
            return null;
        }
        return ru ? l.ru() : l.pl();
    }
}
