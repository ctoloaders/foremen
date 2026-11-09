package com.foremen.service.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.DashboardWidgetDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDashboardWidgetDao;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.AdminService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.dashboard.config.WidgetConfig;
import com.foremen.service.dashboard.config.WidgetConfigValidator;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Admin CRUD over the shared widget library (FOR-10 Requirement 4, 5.6, 8.7, 11.2-11.4, 18.4).
 *
 * <p>Rules: {@code code} is unique ({@code 409 error.dashboard.widget.code.exists}) and immutable;
 * the Widget_Config is validated on create/update ({@code 400 error.dashboard.config.invalid} with
 * {@code violations}); delete is refused for widgets that have placements
 * ({@code 409 error.dashboard.widget.in.use}) and for {@code system_seed} widgets (also in.use, the admin is
 * advised to deactivate); deactivation publishes {@link DashboardWidgetDeactivatedEvent}.
 * Every mutation writes an {@code audit_log} row.
 */
@Service
@RequiredArgsConstructor
public class DashboardWidgetService {

    public static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");
    static final String ENTITY_CLASS = "DashboardWidgetEntity";

    private final DashboardWidgetDao widgetDao;
    private final UserDashboardWidgetDao placementDao;
    private final RoleDao roleDao;
    private final DashboardAccessService accessService;
    private final WidgetResourceResolver resourceResolver;
    private final WidgetConfigValidator configValidator;
    private final AuditLogDao auditLogDao;
    private final ApplicationEventPublisher events;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Create/update payload. {@code code} is ignored on update (immutable). */
    public record WidgetCommand(String code, String nameRu, String namePl, String descriptionRu,
                                String descriptionPl, Map<String, Object> config, Integer colSpan,
                                Integer rowSpan, String projectScope) {
    }

    /** Admin read model (matches the frontend {@code AdminWidget} contract). */
    public record AdminWidgetView(Long id, String code, String nameRu, String namePl, String descriptionRu,
                                  String descriptionPl, Map<String, Object> config, int colSpan, int rowSpan,
                                  String projectScope, boolean active, boolean systemSeed,
                                  List<String> requiredResources, List<String> visibleToRoles, boolean inUse,
                                  String visualization, String dataset) {
    }

    @Transactional(readOnly = true)
    public Page<AdminWidgetView> list(Pageable pageable, String query, Boolean active) {
        Pageable effective = pageable.getSort().isSorted() ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("id"));
        Specification<DashboardWidgetEntity> spec = (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (query != null && !query.isBlank()) {
                String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("code")), like),
                        cb.like(cb.lower(root.get("nameRu")), like),
                        cb.like(cb.lower(root.get("namePl")), like)));
            }
            if (active != null) {
                ps.add(cb.equal(root.get("active"), active));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        List<RoleEntity> roles = roleDao.findAll(Pageable.unpaged()).getContent();
        return widgetDao.findAll(spec, effective).map(w -> toView(w, roles));
    }

    @Transactional(readOnly = true)
    public AdminWidgetView get(Long id) {
        return toView(load(id), allRoles());
    }

    @Transactional
    public AdminWidgetView create(WidgetCommand cmd) {
        String code = cmd.code() == null ? null : cmd.code().trim();
        if (code == null || !CODE.matcher(code).matches()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.dashboard.config.invalid")
                    .withParams(Map.of("violations", List.of(new WidgetConfigValidator.Violation(
                            "code", "code.pattern", "code must match " + CODE.pattern()))));
        }
        if (widgetDao.existsByCode(code)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.dashboard.widget.code.exists", code);
        }
        DashboardWidgetEntity w = new DashboardWidgetEntity();
        w.setCode(code);
        w.setActive(Boolean.TRUE);
        w.setSystemSeed(Boolean.FALSE);
        apply(w, cmd);
        DashboardWidgetEntity saved = widgetDao.save(w);
        audit(null, saved, "CREATE");
        return toView(saved, allRoles());
    }

    @Transactional
    public AdminWidgetView update(Long id, WidgetCommand cmd) {
        DashboardWidgetEntity w = load(id);
        String before = snapshot(w);
        apply(w, cmd);
        DashboardWidgetEntity saved = widgetDao.save(w);
        auditWithBefore(before, saved, "UPDATE");
        return toView(saved, allRoles());
    }

    @Transactional
    public AdminWidgetView activate(Long id) {
        DashboardWidgetEntity w = load(id);
        if (!Boolean.TRUE.equals(w.getActive())) {
            String before = snapshot(w);
            w.setActive(Boolean.TRUE);
            w = widgetDao.save(w);
            auditWithBefore(before, w, "UPDATE");
        }
        return toView(w, allRoles());
    }

    @Transactional
    public AdminWidgetView deactivate(Long id) {
        DashboardWidgetEntity w = load(id);
        if (Boolean.TRUE.equals(w.getActive())) {
            String before = snapshot(w);
            w.setActive(Boolean.FALSE);
            w = widgetDao.save(w);
            auditWithBefore(before, w, "UPDATE");
            events.publishEvent(new DashboardWidgetDeactivatedEvent(w.getId()));
        }
        return toView(w, allRoles());
    }

    @Transactional
    public void delete(Long id) {
        DashboardWidgetEntity w = load(id);
        if (Boolean.TRUE.equals(w.getSystemSeed()) || placementDao.existsByWidgetId(id)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.dashboard.widget.in.use");
        }
        widgetDao.delete(w);
        audit(w, null, "DELETE");
    }

    /** Validates a raw config without saving; returns every violation (empty list = valid). */
    public List<WidgetConfigValidator.Violation> validate(Map<String, Object> config, boolean singleProject) {
        return configValidator.validate(toNode(config), singleProject).violations();
    }

    // ---- internals ----

    private void apply(DashboardWidgetEntity w, WidgetCommand cmd) {
        List<WidgetConfigValidator.Violation> extra = new ArrayList<>();
        if (isBlank(cmd.nameRu())) {
            extra.add(new WidgetConfigValidator.Violation("nameRu", "required", "nameRu is required"));
        }
        if (isBlank(cmd.namePl())) {
            extra.add(new WidgetConfigValidator.Violation("namePl", "required", "namePl is required"));
        }
        if (cmd.colSpan() == null || cmd.colSpan() < 1 || cmd.colSpan() > 4) {
            extra.add(new WidgetConfigValidator.Violation("colSpan", "range", "colSpan must be 1..4"));
        }
        if (cmd.rowSpan() == null || cmd.rowSpan() < 1 || cmd.rowSpan() > 9) {
            extra.add(new WidgetConfigValidator.Violation("rowSpan", "range", "rowSpan must be 1..9"));
        }
        String scope = cmd.projectScope();
        if (!DashboardLayoutService.PROJECT_SCOPE_SINGLE.equals(scope) && !"NONE".equals(scope)) {
            extra.add(new WidgetConfigValidator.Violation("projectScope", "enum",
                    "projectScope must be NONE or SINGLE_PROJECT"));
        }
        List<WidgetConfigValidator.Violation> all = new ArrayList<>(extra);
        WidgetConfigValidator.Result result = null;
        if (cmd.config() == null) {
            all.add(new WidgetConfigValidator.Violation("$", "config.required", "config is required"));
        } else {
            result = configValidator.validate(toNode(cmd.config()),
                    DashboardLayoutService.PROJECT_SCOPE_SINGLE.equals(scope));
            all.addAll(result.violations());
        }
        if (!all.isEmpty()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.dashboard.config.invalid")
                    .withParams(Map.of("violations", all));
        }
        WidgetConfig typed = result.config();
        w.setNameRu(cmd.nameRu().trim());
        w.setNamePl(cmd.namePl().trim());
        w.setDescriptionRu(blankToNull(cmd.descriptionRu()));
        w.setDescriptionPl(blankToNull(cmd.descriptionPl()));
        w.setConfig(cmd.config());
        w.setConfigVersion(typed.schemaVersion() == null ? WidgetConfig.SCHEMA_VERSION : typed.schemaVersion());
        w.setColSpan(cmd.colSpan().shortValue());
        w.setRowSpan(cmd.rowSpan().shortValue());
        w.setProjectScope(scope);
    }

    private AdminWidgetView toView(DashboardWidgetEntity w, List<RoleEntity> roles) {
        Set<String> required = resourceResolver.requiredResources(w);
        List<String> visible = roles.stream()
                .map(RoleEntity::getCode)
                .filter(code -> accessService.missingResources(code, required).isEmpty())
                .sorted()
                .toList();
        Object vis = w.getConfig() == null ? null : w.getConfig().get("visualization");
        Object ds = w.getConfig() == null ? null : w.getConfig().get("dataset");
        return new AdminWidgetView(w.getId(), w.getCode(), w.getNameRu(), w.getNamePl(), w.getDescriptionRu(),
                w.getDescriptionPl(), w.getConfig(), w.getColSpan(), w.getRowSpan(), w.getProjectScope(),
                Boolean.TRUE.equals(w.getActive()), Boolean.TRUE.equals(w.getSystemSeed()),
                required.stream().sorted().toList(), visible, placementDao.existsByWidgetId(w.getId()),
                vis == null ? null : vis.toString(), ds == null ? null : ds.toString());
    }

    private List<RoleEntity> allRoles() {
        return roleDao.findAll(Pageable.unpaged()).getContent().stream()
                .sorted(Comparator.comparing(RoleEntity::getCode)).toList();
    }

    private DashboardWidgetEntity load(Long id) {
        return widgetDao.findById(id).orElseThrow(() ->
                new ForemenApiException(HttpStatus.NOT_FOUND, "error.dashboard.widget.notFound"));
    }

    private static JsonNode toNode(Map<String, Object> config) {
        return JSON.valueToTree(config);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String snapshot(DashboardWidgetEntity w) {
        if (w == null) {
            return null;
        }
        try {
            return AdminService.AUDIT_OBJECT_MAPPER.writeValueAsString(Map.of(
                    "id", String.valueOf(w.getId()), "code", w.getCode(), "nameRu", w.getNameRu(),
                    "namePl", w.getNamePl(), "colSpan", w.getColSpan(), "rowSpan", w.getRowSpan(),
                    "projectScope", w.getProjectScope(), "active", w.getActive(),
                    "systemSeed", w.getSystemSeed(),
                    "config", w.getConfig() == null ? Map.of() : w.getConfig()));
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization_failed\",\"class\":\"" + ENTITY_CLASS + "\"}";
        }
    }

    private void audit(DashboardWidgetEntity before, DashboardWidgetEntity after, String operation) {
        auditWithBefore(snapshot(before), after != null ? after : before, operation, after == null);
    }

    private void auditWithBefore(String beforeSnapshot, DashboardWidgetEntity after, String operation) {
        auditWithBefore(beforeSnapshot, after, operation, false);
    }

    private void auditWithBefore(String beforeSnapshot, DashboardWidgetEntity entity, String operation,
                                 boolean deleted) {
        AuditLogEntity log = new AuditLogEntity();
        log.setEntityClass(ENTITY_CLASS);
        log.setEntityId(entity.getId());
        log.setOperation(operation);
        log.setPerformedBy(currentUser());
        log.setPerformedAt(LocalDateTime.now());
        log.setSnapshotBefore(beforeSnapshot);
        log.setSnapshotAfter(deleted ? null : snapshot(entity));
        auditLogDao.save(log);
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : "SYSTEM";
    }
}
