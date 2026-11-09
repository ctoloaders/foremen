package com.foremen.service.dashboard;

import com.foremen.dao.DashboardWidgetDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * The widget library as seen by one caller (FOR-10 R9.1) and the project picker for Project_Widgets
 * (R12.2). Unavailable widgets are flagged with {@code missingResources}, never hidden.
 */
@Service
@RequiredArgsConstructor
public class DashboardLibraryService {

    public static final String NO_ACCESS = "NO_ACCESS";
    static final int PICKER_LIMIT = 100;

    private final DashboardWidgetDao widgetDao;
    private final UserDao userDao;
    private final DashboardAccessService accessService;
    private final WidgetResourceResolver resourceResolver;
    private final ProjectDao projectDao;
    private final ProjectAccessCache projectAccessCache;

    public record LibraryItem(Long id, String code, String nameRu, String namePl, String descriptionRu,
                              String descriptionPl, String visualization, int colSpan, int rowSpan,
                              String projectScope, boolean available, String unavailableReason,
                              List<String> missingResources) {
    }

    public record ProjectOption(Long id, String name, String address) {
    }

    /** Every active widget, ordered by id, with the caller's availability. */
    @Transactional(readOnly = true)
    public List<LibraryItem> library(Long userId) {
        String role = roleCode(userId);
        return StreamSupport.stream(widgetDao.findAll().spliterator(), false)
                .filter(w -> Boolean.TRUE.equals(w.getActive()))
                .sorted(Comparator.comparing(DashboardWidgetEntity::getId))
                .map(w -> toItem(w, role))
                .toList();
    }

    /**
     * Projects the caller can access (ADMIN: all), status {@code ACTIVE} unless {@code all}, optionally
     * filtered by a case-insensitive substring of name or address; at most {@value #PICKER_LIMIT}.
     */
    @Transactional(readOnly = true)
    public List<ProjectOption> projects(Long userId, String q, boolean all) {
        boolean admin = ForemenPermissionEvaluator.ADMIN_ROLE_CODE.equals(roleCode(userId));
        Iterable<ProjectEntity> candidates = admin ? projectDao.findAll()
                : projectDao.findAllById(projectAccessCache.get(userId));
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return StreamSupport.stream(candidates.spliterator(), false)
                .filter(p -> all || p.getStatus() == ProjectStatus.ACTIVE)
                .filter(p -> needle.isEmpty() || contains(p.getName(), needle) || contains(p.getAddress(), needle))
                .sorted(Comparator.comparing(ProjectEntity::getName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .limit(PICKER_LIMIT)
                .map(p -> new ProjectOption(p.getId(), p.getName(), p.getAddress()))
                .toList();
    }

    private LibraryItem toItem(DashboardWidgetEntity w, String role) {
        Set<String> missing = accessService.missingResources(role, resourceResolver.requiredResources(w));
        Object vis = w.getConfig() == null ? null : w.getConfig().get("visualization");
        boolean available = missing.isEmpty();
        return new LibraryItem(w.getId(), w.getCode(), w.getNameRu(), w.getNamePl(), w.getDescriptionRu(),
                w.getDescriptionPl(), vis == null ? null : vis.toString(), w.getColSpan(), w.getRowSpan(),
                w.getProjectScope(), available, available ? null : NO_ACCESS, List.copyOf(missing));
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private String roleCode(Long userId) {
        return userDao.findById(userId).orElseThrow(() ->
                new ForemenApiException(HttpStatus.NOT_FOUND, "error.user.notFound")).getRole().getCode();
    }
}
