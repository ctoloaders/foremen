package com.foremen.service.dashboard;

import com.foremen.dao.DashboardWidgetDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.UserDashboardWidgetDao;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.UserDashboardWidgetEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.dashboard.appearance.DashboardAppearanceService;
import com.foremen.service.dashboard.appearance.WidgetStyleCatalog;
import com.foremen.service.dashboard.config.WidgetConfig.Visualization;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Per-user dashboard layout (FOR-10 Requirements 1, 3, 9, 12): reads placements with their access state,
 * adds (first-fit or explicit cell) and removes placements. Mutations are serialized per user with
 * {@code pg_advisory_xact_lock(userId)}.
 */
@Service
@RequiredArgsConstructor
public class DashboardLayoutService {

    public static final String PROJECT_SCOPE_SINGLE = "SINGLE_PROJECT";

    private final UserDashboardWidgetDao placementDao;
    private final DashboardWidgetDao widgetDao;
    private final UserDao userDao;
    private final ProjectDao projectDao;
    private final ProjectAccessCache projectAccessCache;
    private final DashboardAccessService accessService;
    private final ObjectProvider<WidgetResourceResolver> resourceResolver;

    /** Optional (field-injected) so lazy default seeding (R15.3) does not alter the constructor. */
    @Autowired(required = false)
    private DashboardDefaultsService defaultsService;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * The user's placements ordered by cell index, each with its computed {@link WidgetState}. Lazily seeds
     * the role defaults when the user has no {@code user_dashboard_state} row yet (R15.3).
     */
    @Transactional
    public List<PlacementView> read(Long userId) {
        if (defaultsService != null) {
            defaultsService.seedFor(userId);
        }
        String role = roleCode(userId);
        List<PlacementView> result = new ArrayList<>();
        for (UserDashboardWidgetEntity p : placementDao.findByUserIdOrdered(userId)) {
            result.add(toView(p, userId, role));
        }
        return result;
    }

    @Transactional
    public PlacementView addPlacement(Long userId, Long widgetId, Integer cellIndex, Long projectId) {
        lockUser(userId);

        DashboardWidgetEntity widget = widgetDao.findById(widgetId).orElseThrow(() ->
                new ForemenApiException(HttpStatus.NOT_FOUND, "error.dashboard.widget.notFound"));
        if (!Boolean.TRUE.equals(widget.getActive())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.dashboard.widget.inactive");
        }
        UserEntity user = userDao.findById(userId).orElseThrow(() ->
                new ForemenApiException(HttpStatus.NOT_FOUND, "error.user.notFound"));
        String role = user.getRole().getCode();
        Set<String> missing = accessService.missingResources(role, required(widget));
        if (!missing.isEmpty()) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.dashboard.widget.disabled");
        }

        boolean projectWidget = isProjectWidget(widget);
        ProjectEntity project = null;
        if (projectWidget) {
            if (projectId == null) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.dashboard.project.required");
            }
            boolean admin = ForemenPermissionEvaluator.ADMIN_ROLE_CODE.equals(role);
            boolean accessible = admin || projectAccessCache.get(userId).contains(projectId);
            project = accessible ? projectDao.findById(projectId).orElse(null) : null;
            if (project == null) {
                throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.dashboard.project.forbidden");
            }
        } else if (projectId != null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.dashboard.project.unexpected");
        }

        List<UserDashboardWidgetEntity> existing = placementDao.findByUserIdOrdered(userId);
        for (UserDashboardWidgetEntity p : existing) {
            if (isDuplicate(p, widgetId, projectWidget, projectId)) {
                throw new ForemenApiException(HttpStatus.CONFLICT, "error.dashboard.widget.duplicate");
            }
        }

        int cs = widget.getColSpan();
        int rs = widget.getRowSpan();
        long occupied = GridMath.occupied(existing.stream()
                .map(p -> new GridMath.Rect(p.getCellIndex(), p.getWidget().getColSpan(), p.getWidget().getRowSpan()))
                .toList());
        int cell;
        if (cellIndex != null) {
            if (!GridMath.fits(cellIndex, cs, rs)) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.dashboard.cell.invalid");
            }
            if ((GridMath.footprint(cellIndex, cs, rs) & occupied) != 0L) {
                throw new ForemenApiException(HttpStatus.CONFLICT, "error.dashboard.cell.occupied");
            }
            cell = cellIndex;
        } else {
            OptionalInt fit = GridMath.firstFit(occupied, cs, rs, 0);
            if (fit.isEmpty()) {
                throw new ForemenApiException(HttpStatus.CONFLICT, "error.dashboard.grid.full");
            }
            cell = fit.getAsInt();
        }

        UserDashboardWidgetEntity placement = new UserDashboardWidgetEntity();
        placement.setUser(user);
        placement.setWidget(widget);
        placement.setCellIndex((short) cell);
        placement.setProject(project);
        UserDashboardWidgetEntity saved = placementDao.save(placement);
        return toView(saved, userId, role);
    }

    /** Removes the caller's own placement. Missing: 404; foreign: 403 {@code error.dashboard.forbidden}. */
    @Transactional
    public void removePlacement(Long userId, Long placementId) {
        lockUser(userId);
        UserDashboardWidgetEntity p = placementDao.findById(placementId).orElseThrow(() ->
                new ForemenApiException(HttpStatus.NOT_FOUND, "error.dashboard.placement.notFound"));
        if (!p.getUser().getId().equals(userId)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.dashboard.forbidden");
        }
        placementDao.delete(p);
    }

    // ------------------------------------------------------------------ helpers

    static boolean isDuplicate(UserDashboardWidgetEntity p, Long widgetId, boolean projectWidget, Long projectId) {
        if (!p.getWidget().getId().equals(widgetId)) {
            return false;
        }
        if (!projectWidget) {
            return true;
        }
        return p.getProject() != null && p.getProject().getId().equals(projectId);
    }

    private PlacementView toView(UserDashboardWidgetEntity p, Long userId, String role) {
        DashboardWidgetEntity w = p.getWidget();
        Set<String> required = required(w);
        boolean projectWidget = isProjectWidget(w);
        Long boundProject = p.getProject() == null ? null : p.getProject().getId();
        WidgetState state = accessService.evaluate(userId, role, Boolean.TRUE.equals(w.getActive()),
                required, projectWidget, boundProject);
        Set<String> missing = accessService.missingResources(role, required);
        Map<String, Object> cfg = w.getConfig();
        Object vis = cfg == null ? null : cfg.get("visualization");
        boolean dateAware = cfg != null && cfg.get("dateField") != null;
        Visualization viz = DashboardAppearanceService.parse(vis == null ? null : vis.toString());
        Object presentation = cfg == null ? null : cfg.get("presentation");
        Object cfgStyle = presentation instanceof Map<?, ?> pm ? pm.get("style") : null;
        String defaultStyle = DashboardAppearanceService.effectiveStyle(viz,
                cfgStyle == null ? null : cfgStyle.toString(), null);
        List<String> available = viz == null ? List.of() : WidgetStyleCatalog.stylesFor(viz);
        return new PlacementView(p.getId(), w.getId(), w.getCode(), w.getNameRu(), w.getNamePl(),
                p.getCellIndex(), w.getColSpan(), w.getRowSpan(), vis == null ? null : vis.toString(),
                boundProject, p.getProject() == null ? null : p.getProject().getName(), dateAware, state,
                List.copyOf(missing), defaultStyle, defaultStyle, available);
    }

    private Set<String> required(DashboardWidgetEntity widget) {
        WidgetResourceResolver resolver = resourceResolver.getIfAvailable();
        if (resolver == null) {
            throw new IllegalStateException("No WidgetResourceResolver configured");
        }
        return resolver.requiredResources(widget);
    }

    private boolean isProjectWidget(DashboardWidgetEntity w) {
        return PROJECT_SCOPE_SINGLE.equals(w.getProjectScope());
    }

    private String roleCode(Long userId) {
        return userDao.findById(userId).orElseThrow(() ->
                new ForemenApiException(HttpStatus.NOT_FOUND, "error.user.notFound")).getRole().getCode();
    }

    private void lockUser(Long userId) {
        entityManager.createNativeQuery("SELECT CAST(pg_advisory_xact_lock(:k) AS text)")
                .setParameter("k", userId)
                .getSingleResult();
    }
}
