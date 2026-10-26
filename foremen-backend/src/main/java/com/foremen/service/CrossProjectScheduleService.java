package com.foremen.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.controller.model.companyschedule.CompanyScheduleQuery;
import com.foremen.controller.model.companyschedule.CompanyScheduleView;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.ProjectScheduleDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectScheduleBarEntity;
import com.foremen.dao.model.ProjectScheduleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.service.companyschedule.CrossProjectScheduleProjector;
import com.foremen.service.companyschedule.ForemanInput;
import com.foremen.service.companyschedule.ProjectInput;
import com.foremen.service.companyschedule.WorkerBarInput;

/**
 * FOR-07-01 (Requirements 1, 2, 6, 7, 10; design §"Service"): the bespoke, <strong>read-only</strong>
 * service behind the cross-project staffing projection ({@code GET /api/company-schedule}). It
 * resolves the caller's access scope, loads every Accessible_Project's schedule, members, and project
 * row through existing DAOs, flattens them into the DB-free {@link ProjectInput}s, and delegates all
 * decision logic to the pure {@link CrossProjectScheduleProjector}.
 *
 * <p><strong>Not a {@link ProjectScopedService}.</strong> Unlike the per-project
 * {@code ProjectScheduleService}, this endpoint is a bespoke aggregation read, not an
 * {@code AdminController} CRUD surface, so scope is applied explicitly here (Requirement 6.5, 9.5)
 * rather than through the generic list filter / by-id guards. The access decision nonetheless reuses
 * the <em>same</em> exact-authority ADMIN check and the <em>same</em> {@link ProjectAccessCache} the
 * per-project service uses, so a membership invalidation is honored identically (Requirement 6.4,
 * 6.5).
 *
 * <p><strong>Scope resolution (Requirement 6).</strong>
 * <ul>
 *   <li>Exact-authority ADMIN ({@code ROLE_ADMIN} / {@code ADMIN}) ⇒ {@code null} allowed set = all
 *       projects (ADMIN bypass).</li>
 *   <li>Non-ADMIN ⇒ {@link ProjectAccessCache#get(Long)} for the numeric principal user id.</li>
 *   <li>No authenticated / non-numeric caller, or a non-ADMIN with an empty allowed set ⇒ an empty
 *       projection (Requirement 6.3, 10.1), never an error.</li>
 * </ul>
 * The supplied {@code Project_Filter} ({@link CompanyScheduleQuery#projectIds()}) is then intersected
 * with the allowed set, so an out-of-scope id never widens scope and is silently ignored
 * (Requirement 7.3, 7.7).
 *
 * <p><strong>Read-only (Requirement 7.9).</strong> The service is {@code @Transactional(readOnly =
 * true)}; it creates / updates / deletes no schedule row and bumps no {@code @Version}.
 */
@Service
@Transactional(readOnly = true)
public class CrossProjectScheduleService {

    /** The project-role code of a WORKER member (source of Worker_Category_Bars). */
    private static final String WORKER_ROLE_CODE = "WORKER";

    /** The project-role code of a FOREMAN member (source of synthesized Foreman_Span_Bars). */
    private static final String FOREMAN_ROLE_CODE = "FOREMAN";

    private final ProjectScheduleDao projectScheduleDao;
    private final ProjectMemberDao projectMemberDao;
    private final ProjectDao projectDao;
    private final ProjectAccessCache projectAccessCache;

    public CrossProjectScheduleService(ProjectScheduleDao projectScheduleDao,
                                       ProjectMemberDao projectMemberDao,
                                       ProjectDao projectDao,
                                       ProjectAccessCache projectAccessCache) {
        this.projectScheduleDao = projectScheduleDao;
        this.projectMemberDao = projectMemberDao;
        this.projectDao = projectDao;
        this.projectAccessCache = projectAccessCache;
    }

    /**
     * Builds the cross-project staffing projection for the authenticated caller within the supplied
     * filters (FOR-07-01 Requirements 1, 2, 6, 7, 10). This is a pure read: it loads the accessible
     * projects' schedules / members / rows, flattens them into {@link ProjectInput}s, and hands them
     * to {@link CrossProjectScheduleProjector#project(List, CompanyScheduleQuery, boolean)}.
     *
     * <p>The {@code Date_Window} ordering guard ({@code from > to} → HTTP 400) is a controller concern
     * (Requirement 7.8) and is already validated before this method is called.
     *
     * @param query the normalized server-side filters; {@code null} is treated as "no filters"
     * @return the immutable {@link CompanyScheduleView}; empty {@code rows} and {@code legend} for an
     *         empty projection (no authenticated caller, empty allowed set, or everything filtered out)
     */
    public CompanyScheduleView getProjection(CompanyScheduleQuery query) {
        // 1 — resolve the caller's allowed project id set: null = exact-authority ADMIN (all projects);
        //     empty = a non-ADMIN with no accessible project (or no identifiable caller) → empty view.
        Set<Long> allowed = resolveAllowedProjectIds();
        boolean adminAllProjects = allowed == null;
        if (!adminAllProjects && allowed.isEmpty()) {
            return new CompanyScheduleView(List.of(), List.of()); // Requirement 6.3, 10.1
        }

        // 2 — the in-scope project ids: the allowed set intersected with any supplied Project_Filter.
        //     For an ADMIN (all projects) the in-scope set is exactly the (intersected) Project_Filter,
        //     or every project when no Project_Filter is supplied. An out-of-scope filter id is simply
        //     dropped here and never widens scope (Requirement 7.3, 7.7).
        List<Long> projectFilter = query == null ? null : query.projectIds();
        Set<Long> inScope = resolveInScopeProjectIds(allowed, adminAllProjects, projectFilter);
        if (inScope.isEmpty()) {
            return new CompanyScheduleView(List.of(), List.of()); // nothing accessible after intersect
        }

        // 3 — load the in-scope projects, their schedules (batched), and their members.
        List<ProjectEntity> projects = projectDao.findAllByIdIn(inScope);
        if (projects.isEmpty()) {
            return new CompanyScheduleView(List.of(), List.of());
        }

        Map<Long, ProjectScheduleEntity> schedulesByProjectId = new LinkedHashMap<>();
        for (ProjectScheduleEntity schedule : projectScheduleDao.findByProjectIdIn(inScope)) {
            if (schedule.getProject() != null && schedule.getProject().getId() != null) {
                schedulesByProjectId.put(schedule.getProject().getId(), schedule);
            }
        }

        // 4 — flatten each project into a DB-free ProjectInput for the pure projector.
        List<ProjectInput> inputs = new ArrayList<>(projects.size());
        for (ProjectEntity project : projects) {
            inputs.add(toProjectInput(project, schedulesByProjectId.get(project.getId())));
        }

        // 5 — delegate all decision logic to the pure projector, localizing category names per locale.
        return CrossProjectScheduleProjector.project(inputs, query, isRussianLocale());
    }

    /**
     * Resolves the caller's allowed project id set (Requirement 6): {@code null} for an exact-authority
     * ADMIN (all projects, ADMIN bypass); otherwise the {@link ProjectAccessCache} set for the numeric
     * principal user id. A missing / non-authenticated / non-numeric caller yields an empty set (which
     * the caller maps to an empty projection), never the ADMIN {@code null}.
     *
     * @return {@code null} for ADMIN (all projects); the allowed set for a resolvable non-ADMIN caller;
     *         an empty set when no caller can be resolved
     */
    Set<Long> resolveAllowedProjectIds() {
        if (isCallerAdmin()) {
            return null; // ADMIN bypass: all projects (Requirement 6.1)
        }
        Long userId = callerUserId();
        if (userId == null) {
            return Set.of(); // no identifiable non-ADMIN caller → empty projection (Requirement 6.3)
        }
        Set<Long> allowed = projectAccessCache.get(userId);
        return allowed == null ? Set.of() : allowed;
    }

    /**
     * The in-scope project ids: the allowed set intersected with any supplied {@code Project_Filter}.
     * For an ADMIN ({@code adminAllProjects}) with no filter this is every project id; with a filter
     * it is exactly the filter (every id is in scope for ADMIN). For a non-ADMIN it is the allowed set,
     * further narrowed to the filter when one is supplied. An out-of-scope filter id is dropped and
     * never widens scope (Requirement 7.3, 7.7).
     */
    private Set<Long> resolveInScopeProjectIds(Set<Long> allowed, boolean adminAllProjects,
                                               List<Long> projectFilter) {
        if (adminAllProjects) {
            if (projectFilter == null) {
                // All projects: resolve the full id set once.
                Set<Long> all = new java.util.LinkedHashSet<>();
                for (ProjectEntity project : projectDao.findAll()) {
                    if (project.getId() != null) {
                        all.add(project.getId());
                    }
                }
                return all;
            }
            return new java.util.LinkedHashSet<>(projectFilter);
        }
        if (projectFilter == null) {
            return new java.util.LinkedHashSet<>(allowed);
        }
        Set<Long> inScope = new java.util.LinkedHashSet<>();
        for (Long id : projectFilter) {
            if (allowed.contains(id)) { // intersect; ignore out-of-scope ids (Requirement 7.3, 7.7)
                inScope.add(id);
            }
        }
        return inScope;
    }

    /**
     * Flattens one Accessible_Project into a {@link ProjectInput}: its id / name / anchor-end dates, the
     * Real_Bars (non-null-member schedule bars) of its WORKER members as {@link WorkerBarInput}s, and
     * its ACTIVE FOREMAN members as {@link ForemanInput}s. A project with no schedule row contributes
     * no worker bar but still contributes foreman spans (Requirement 10.2).
     *
     * <p>Only a bar whose {@code projectMember} is non-null becomes a {@link WorkerBarInput}; an
     * {@code Uncovered_Category} null-member bar is excluded here so it never reaches the projector
     * (Requirement 2.1). The foreman-also-worker precedence itself is applied by the projector.
     */
    private ProjectInput toProjectInput(ProjectEntity project, ProjectScheduleEntity schedule) {
        long projectId = project.getId();
        List<ProjectMemberEntity> members = projectMemberDao.findByProjectIdWithUserAndRole(projectId);

        // The foreman user ids used to resolve a worker bar's role code (a member may hold either role).
        List<ForemanInput> foremen = new ArrayList<>();
        for (ProjectMemberEntity member : members) {
            if (isActiveWithRole(member, FOREMAN_ROLE_CODE) && member.getUser() != null) {
                UserEntity user = member.getUser();
                foremen.add(new ForemanInput(user.getId(), user.getName()));
            }
        }

        List<WorkerBarInput> workerBars = new ArrayList<>();
        if (schedule != null && schedule.getBars() != null) {
            for (ProjectScheduleBarEntity bar : schedule.getBars()) {
                WorkerBarInput workerBar = toWorkerBarInput(bar);
                if (workerBar != null) {
                    workerBars.add(workerBar);
                }
            }
        }

        return new ProjectInput(
                projectId,
                project.getName(),
                project.getStartDate(),
                project.getEndDate(),
                workerBars,
                foremen);
    }

    /**
     * Maps one {@code project_schedule_bars} row to a {@link WorkerBarInput}, or {@code null} when it
     * is not a Real_Bar the projector should see: a null {@code projectMember} (the virtual
     * Uncovered_Category bar, Requirement 2.1), a missing user / category, or missing half-day fields.
     * The bar's role code is read from the member's {@code projectRole.code}.
     */
    private WorkerBarInput toWorkerBarInput(ProjectScheduleBarEntity bar) {
        if (bar == null) {
            return null;
        }
        ProjectMemberEntity member = bar.getProjectMember();
        if (member == null || member.getUser() == null) {
            return null; // Uncovered_Category null-member bar — excluded upstream (Requirement 2.1)
        }
        WorkCategoryEntity category = bar.getWorkCategory();
        if (category == null || category.getId() == null) {
            return null;
        }
        if (bar.getStartHalfDays() == null || bar.getDurationHalfDays() == null) {
            return null; // defensive: a persisted bar always has both (CHECK >= 1)
        }
        UserEntity user = member.getUser();
        String roleCode = member.getProjectRole() == null ? null : member.getProjectRole().getCode();
        return new WorkerBarInput(
                user.getId(),
                user.getName(),
                roleCode,
                category.getId(),
                category.getNameRU(),
                category.getNamePL(),
                bar.getStartHalfDays(),
                bar.getDurationHalfDays());
    }

    /** Whether a member is ACTIVE and holds the given project-role code. */
    private static boolean isActiveWithRole(ProjectMemberEntity member, String roleCode) {
        return member != null
                && member.getAssignmentStatus() == AssignmentStatus.ACTIVE
                && member.getProjectRole() != null
                && roleCode.equals(member.getProjectRole().getCode());
    }

    /** True iff the authenticated caller carries the exact ADMIN authority ({@code ROLE_ADMIN}/{@code ADMIN}). */
    private static boolean isCallerAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if ("ROLE_ADMIN".equals(a) || "ADMIN".equals(a)) {
                return true;
            }
        }
        return false;
    }

    /** The authenticated caller's numeric user id, or {@code null} when it cannot be resolved. */
    private static Long callerUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True iff the request locale language is {@code ru} (case-insensitive); mirrors the app's i18n choice. */
    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
