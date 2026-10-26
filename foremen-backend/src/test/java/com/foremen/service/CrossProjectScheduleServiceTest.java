package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.controller.model.companyschedule.BarKind;
import com.foremen.controller.model.companyschedule.CompanyScheduleQuery;
import com.foremen.controller.model.companyschedule.CompanyScheduleView;
import com.foremen.controller.model.companyschedule.MemberRow;
import com.foremen.controller.model.companyschedule.ProjectedBar;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.ProjectScheduleDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectScheduleBarEntity;
import com.foremen.dao.model.ProjectScheduleEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;

/**
 * Plain Mockito unit tests for {@link CrossProjectScheduleService} (FOR-07-01 task 4.3). The three
 * DAOs ({@link ProjectScheduleDao}, {@link ProjectMemberDao}, {@link ProjectDao}) and the
 * {@link ProjectAccessCache} are mocked; the caller's role / numeric principal is driven through
 * {@link SecurityContextHolder} and the request language through {@link LocaleContextHolder},
 * mirroring {@code ProjectScheduleServiceTest}.
 *
 * <p>Covered behavior (Requirements 6.1, 6.2, 6.3, 7.9, 10.1, 10.2):
 * <ul>
 *   <li>an exact-authority ADMIN sees every project (the {@code null} allowed-set / {@code findAll}
 *       path, Requirement 6.1);</li>
 *   <li>a non-ADMIN is restricted to exactly the {@link ProjectAccessCache} set for its user id
 *       (Requirement 6.2);</li>
 *   <li>a non-ADMIN with an empty allowed set yields an empty projection with no DAO load
 *       (Requirement 6.3, 10.1);</li>
 *   <li>an accessible project with no schedule row still yields its foreman spans
 *       (Requirement 10.2);</li>
 *   <li>the service is read-only — no {@code save}/{@code delete} is ever called on any DAO
 *       (Requirement 7.9).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CrossProjectScheduleServiceTest {

    @Mock
    private ProjectScheduleDao projectScheduleDao;
    @Mock
    private ProjectMemberDao projectMemberDao;
    @Mock
    private ProjectDao projectDao;
    @Mock
    private ProjectAccessCache projectAccessCache;

    private CrossProjectScheduleService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new CrossProjectScheduleService(
                projectScheduleDao, projectMemberDao, projectDao, projectAccessCache);
        // Default the request locale to Russian so category names are deterministic across tests.
        LocaleContextHolder.setLocale(new Locale("ru"));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        LocaleContextHolder.resetLocaleContext();
    }

    // ---- test-data builders ------------------------------------------------

    private static void authenticate(String principal, String authority) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, "n/a", List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static ProjectEntity project(long id, String name, LocalDate start, LocalDate end) {
        ProjectEntity p = new ProjectEntity();
        p.setId(id);
        p.setName(name);
        p.setStartDate(start);
        p.setEndDate(end);
        return p;
    }

    private static WorkCategoryEntity category(Long id, String nameRU, String namePL) {
        WorkCategoryEntity c = new WorkCategoryEntity();
        c.setId(id);
        c.setCode("C" + id);
        c.setNameRU(nameRU);
        c.setNamePL(namePL);
        c.setActive(true);
        return c;
    }

    private static UserEntity user(long id, String name) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setName(name);
        return u;
    }

    private static RoleEntity role(String code) {
        RoleEntity r = new RoleEntity();
        r.setId(900L + code.hashCode());
        r.setCode(code);
        return r;
    }

    /** An ACTIVE project member with the given user, role code, and owning project. */
    private static ProjectMemberEntity member(long memberId, UserEntity user, String roleCode, long projectId) {
        ProjectMemberEntity m = new ProjectMemberEntity();
        m.setId(memberId);
        m.setUser(user);
        m.setProjectRole(role(roleCode));
        m.setProjectId(projectId);
        m.setAssignmentStatus(AssignmentStatus.ACTIVE);
        return m;
    }

    /** A schedule aggregate (owned by {@code project}) carrying a single per-worker category bar. */
    private static ProjectScheduleEntity scheduleWithWorkerBar(long scheduleId, ProjectEntity project,
                                                               WorkCategoryEntity cat,
                                                               ProjectMemberEntity workerMember,
                                                               int startHalfDays, int durationHalfDays) {
        ProjectScheduleEntity schedule = new ProjectScheduleEntity();
        schedule.setId(scheduleId);
        schedule.setVersion(1L);
        schedule.setProject(project);
        ProjectScheduleBarEntity bar = new ProjectScheduleBarEntity();
        bar.setId(scheduleId + 1);
        bar.setSchedule(schedule);
        bar.setWorkCategory(cat);
        bar.setProjectMember(workerMember);
        bar.setStartHalfDays(startHalfDays);
        bar.setDurationHalfDays(durationHalfDays);
        schedule.getBars().add(bar);
        return schedule;
    }

    /** Asserts no DAO ever had a write method invoked (read-only service, Requirement 7.9). */
    private void assertNoWrites() {
        verify(projectScheduleDao, never()).save(any());
        verify(projectScheduleDao, never()).saveAll(any());
        verify(projectScheduleDao, never()).delete(any(ProjectScheduleEntity.class));
        verify(projectScheduleDao, never()).deleteById(any());
        verify(projectScheduleDao, never()).deleteAll();
        verify(projectMemberDao, never()).save(any());
        verify(projectMemberDao, never()).saveAll(any());
        verify(projectMemberDao, never()).delete(any(ProjectMemberEntity.class));
        verify(projectMemberDao, never()).deleteById(any());
        verify(projectMemberDao, never()).deleteAll();
        verify(projectDao, never()).save(any());
        verify(projectDao, never()).saveAll(any());
        verify(projectDao, never()).delete(any(ProjectEntity.class));
        verify(projectDao, never()).deleteById(any());
        verify(projectDao, never()).deleteAll();
    }

    // =======================================================================
    // ADMIN scope — all projects via the null allowed-set / findAll path (R6.1)
    // =======================================================================

    @Nested
    @DisplayName("ADMIN scope (R6.1): all projects via the findAll path")
    class AdminScope {

        @Test
        @DisplayName("an exact-authority ADMIN loads every project (findAll), never consulting the access cache")
        void adminSeesAllProjects() {
            authenticate("1", "ROLE_ADMIN");
            ProjectEntity p1 = project(10L, "Alpha", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1));
            ProjectEntity p2 = project(20L, "Beta", LocalDate.of(2025, 3, 1), LocalDate.of(2025, 4, 1));
            // ADMIN takes the "all projects" branch: resolveInScope uses findAll() to get every id.
            when(projectDao.findAll()).thenReturn(List.of(p1, p2));
            when(projectDao.findAllByIdIn(any())).thenReturn(List.of(p1, p2));
            when(projectScheduleDao.findByProjectIdIn(any())).thenReturn(List.of());
            // Each project has one ACTIVE FOREMAN member → one foreman span row per project's foreman.
            UserEntity foremanA = user(100L, "Foreman A");
            UserEntity foremanB = user(200L, "Foreman B");
            when(projectMemberDao.findByProjectIdWithUserAndRole(10L))
                    .thenReturn(List.of(member(1L, foremanA, "FOREMAN", 10L)));
            when(projectMemberDao.findByProjectIdWithUserAndRole(20L))
                    .thenReturn(List.of(member(2L, foremanB, "FOREMAN", 20L)));

            CompanyScheduleView view = service.getProjection(new CompanyScheduleQuery(null, null, null, null, null));

            // Both projects contributed their foreman span → two member rows, two legend entries.
            assertThat(view.rows()).hasSize(2);
            assertThat(view.rows()).extracting(MemberRow::userId).containsExactlyInAnyOrder(100L, 200L);
            assertThat(view.legend()).extracting("projectId").containsExactlyInAnyOrder(10L, 20L);
            // ADMIN bypass: the ProjectAccessCache is never consulted.
            verifyNoInteractions(projectAccessCache);
            assertNoWrites();
        }
    }

    // =======================================================================
    // Non-ADMIN scope — restricted to the ProjectAccessCache set (R6.2)
    // =======================================================================

    @Nested
    @DisplayName("non-ADMIN scope (R6.2): restricted to the ProjectAccessCache set")
    class NonAdminScope {

        @Test
        @DisplayName("a non-ADMIN sees only its cache-allowed projects; findAll is never called")
        void nonAdminRestrictedToCacheSet() {
            authenticate("7", "ROLE_MANAGER");
            // The cache grants access to project 10 only (project 20 exists but is out of scope).
            when(projectAccessCache.get(7L)).thenReturn(Set.of(10L));
            ProjectEntity p1 = project(10L, "Alpha", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1));
            when(projectDao.findAllByIdIn(Set.of(10L))).thenReturn(List.of(p1));
            when(projectScheduleDao.findByProjectIdIn(Set.of(10L))).thenReturn(List.of());
            UserEntity foremanA = user(100L, "Foreman A");
            when(projectMemberDao.findByProjectIdWithUserAndRole(10L))
                    .thenReturn(List.of(member(1L, foremanA, "FOREMAN", 10L)));

            CompanyScheduleView view = service.getProjection(new CompanyScheduleQuery(null, null, null, null, null));

            // Only the accessible project's foreman surfaced.
            assertThat(view.rows()).singleElement()
                    .satisfies(row -> assertThat(row.userId()).isEqualTo(100L));
            assertThat(view.legend()).extracting("projectId").containsExactly(10L);
            // The non-ADMIN path never resolves the full project set.
            verify(projectDao, never()).findAll();
            // The out-of-scope project 20 is never loaded.
            verify(projectMemberDao, never()).findByProjectIdWithUserAndRole(20L);
            assertNoWrites();
        }

        @Test
        @DisplayName("a Project_Filter is intersected with the allowed set — an out-of-scope id never widens scope")
        void projectFilterIsIntersectedWithAllowedSet() {
            authenticate("7", "ROLE_MANAGER");
            when(projectAccessCache.get(7L)).thenReturn(Set.of(10L, 30L));
            ProjectEntity p1 = project(10L, "Alpha", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1));
            // Filter asks for 10 (allowed) and 20 (NOT allowed): only 10 should survive the intersect.
            when(projectDao.findAllByIdIn(Set.of(10L))).thenReturn(List.of(p1));
            when(projectScheduleDao.findByProjectIdIn(Set.of(10L))).thenReturn(List.of());
            UserEntity foremanA = user(100L, "Foreman A");
            when(projectMemberDao.findByProjectIdWithUserAndRole(10L))
                    .thenReturn(List.of(member(1L, foremanA, "FOREMAN", 10L)));

            CompanyScheduleView view = service.getProjection(
                    new CompanyScheduleQuery(null, List.of(10L, 20L), null, null, null));

            assertThat(view.legend()).extracting("projectId").containsExactly(10L);
            verify(projectMemberDao, never()).findByProjectIdWithUserAndRole(20L);
            assertNoWrites();
        }
    }

    // =======================================================================
    // Empty allowed set — empty projection with NO DAO load (R6.3, R10.1)
    // =======================================================================

    @Nested
    @DisplayName("empty allowed set (R6.3, R10.1): empty projection, no DAO load")
    class EmptyAllowedSet {

        @Test
        @DisplayName("a non-ADMIN with an empty cache set gets an empty view and no project/schedule/member load")
        void emptyAllowedSetYieldsEmptyProjection() {
            authenticate("7", "ROLE_MANAGER");
            when(projectAccessCache.get(7L)).thenReturn(Set.of());

            CompanyScheduleView view = service.getProjection(new CompanyScheduleQuery(null, null, null, null, null));

            assertThat(view.rows()).isEmpty();
            assertThat(view.legend()).isEmpty();
            // No DAO load at all — the empty allowed set short-circuits before any query.
            verifyNoInteractions(projectDao, projectScheduleDao, projectMemberDao);
        }

        @Test
        @DisplayName("a null cache set (no memberships) is treated as empty — empty view, no DAO load")
        void nullCacheSetYieldsEmptyProjection() {
            authenticate("7", "ROLE_MANAGER");
            when(projectAccessCache.get(7L)).thenReturn(null);

            CompanyScheduleView view = service.getProjection(new CompanyScheduleQuery(null, null, null, null, null));

            assertThat(view.rows()).isEmpty();
            assertThat(view.legend()).isEmpty();
            verifyNoInteractions(projectDao, projectScheduleDao, projectMemberDao);
        }

        @Test
        @DisplayName("an unauthenticated / non-numeric caller is an empty projection, never an error")
        void nonNumericCallerYieldsEmptyProjection() {
            // A non-numeric principal cannot be resolved to a user id → empty allowed set.
            authenticate("not-a-number", "ROLE_MANAGER");
            lenient().when(projectAccessCache.get(anyLong())).thenReturn(Set.of(10L));

            CompanyScheduleView view = service.getProjection(new CompanyScheduleQuery(null, null, null, null, null));

            assertThat(view.rows()).isEmpty();
            assertThat(view.legend()).isEmpty();
            verifyNoInteractions(projectDao, projectScheduleDao, projectMemberDao);
        }
    }

    // =======================================================================
    // Schedule-less accessible project still yields foreman spans (R10.2)
    // =======================================================================

    @Nested
    @DisplayName("schedule-less accessible project (R10.2): still yields foreman spans")
    class ScheduleLessProject {

        @Test
        @DisplayName("an accessible project with no schedule row contributes its foreman span (no worker bar)")
        void scheduleLessProjectStillYieldsForemanSpan() {
            authenticate("7", "ROLE_MANAGER");
            when(projectAccessCache.get(7L)).thenReturn(Set.of(10L));
            ProjectEntity p1 = project(10L, "Alpha", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1));
            when(projectDao.findAllByIdIn(Set.of(10L))).thenReturn(List.of(p1));
            // No schedule row for the project → findByProjectIdIn returns nothing.
            when(projectScheduleDao.findByProjectIdIn(Set.of(10L))).thenReturn(List.of());
            UserEntity foreman = user(100L, "Foreman A");
            UserEntity worker = user(101L, "Worker W");
            // One ACTIVE FOREMAN and one ACTIVE WORKER member; with no schedule the worker has no bar.
            when(projectMemberDao.findByProjectIdWithUserAndRole(10L)).thenReturn(List.of(
                    member(1L, foreman, "FOREMAN", 10L),
                    member(2L, worker, "WORKER", 10L)));

            CompanyScheduleView view = service.getProjection(new CompanyScheduleQuery(null, null, null, null, null));

            // Only the foreman row is present (the worker contributes no bar without a schedule).
            assertThat(view.rows()).singleElement().satisfies(row -> {
                assertThat(row.userId()).isEqualTo(100L);
                assertThat(row.companyRole()).isEqualTo("FOREMAN");
                assertThat(row.bars()).singleElement().satisfies(bar -> {
                    assertThat(bar.kind()).isEqualTo(BarKind.FOREMAN_SPAN);
                    assertThat(bar.projectId()).isEqualTo(10L);
                    assertThat(bar.startDate()).isEqualTo(LocalDate.of(2025, 1, 1));
                    assertThat(bar.endDate()).isEqualTo(LocalDate.of(2025, 2, 1));
                });
            });
            assertNoWrites();
        }

        @Test
        @DisplayName("a schedule-bearing project yields the worker category bar alongside other projects' foreman spans")
        void scheduleBearingProjectYieldsWorkerBar() {
            authenticate("7", "ROLE_MANAGER");
            when(projectAccessCache.get(7L)).thenReturn(Set.of(10L));
            ProjectEntity p1 = project(10L, "Alpha", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1));
            when(projectDao.findAllByIdIn(Set.of(10L))).thenReturn(List.of(p1));
            UserEntity worker = user(101L, "Worker W");
            ProjectMemberEntity workerMember = member(2L, worker, "WORKER", 10L);
            WorkCategoryEntity cat = category(5L, "Демонтаж", "Rozbiórka");
            ProjectScheduleEntity schedule = scheduleWithWorkerBar(900L, p1, cat, workerMember, 1, 4);
            when(projectScheduleDao.findByProjectIdIn(Set.of(10L))).thenReturn(List.of(schedule));
            when(projectMemberDao.findByProjectIdWithUserAndRole(10L)).thenReturn(List.of(workerMember));

            CompanyScheduleView view = service.getProjection(new CompanyScheduleQuery(null, null, null, null, null));

            assertThat(view.rows()).singleElement().satisfies(row -> {
                assertThat(row.userId()).isEqualTo(101L);
                assertThat(row.companyRole()).isEqualTo("WORKER");
                assertThat(row.bars()).singleElement().satisfies(bar -> {
                    assertThat(bar.kind()).isEqualTo(BarKind.WORKER_CATEGORY);
                    assertThat(bar.workCategoryId()).isEqualTo(5L);
                    // Russian locale (default) → Russian category name.
                    assertThat(bar.workCategoryName()).isEqualTo("Демонтаж");
                });
            });
            assertNoWrites();
        }
    }

    // =======================================================================
    // Read-only — no write on any DAO across a representative projection (R7.9)
    // =======================================================================

    @Test
    @DisplayName("the service never calls a write/save/delete method on any DAO (read-only, R7.9)")
    void serviceIsReadOnly() {
        authenticate("1", "ROLE_ADMIN");
        ProjectEntity p1 = project(10L, "Alpha", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1));
        UserEntity worker = user(101L, "Worker W");
        ProjectMemberEntity workerMember = member(2L, worker, "WORKER", 10L);
        WorkCategoryEntity cat = category(5L, "Демонтаж", "Rozbiórka");
        ProjectScheduleEntity schedule = scheduleWithWorkerBar(900L, p1, cat, workerMember, 1, 4);
        when(projectDao.findAll()).thenReturn(List.of(p1));
        when(projectDao.findAllByIdIn(any())).thenReturn(List.of(p1));
        when(projectScheduleDao.findByProjectIdIn(anyCollection())).thenReturn(List.of(schedule));
        when(projectMemberDao.findByProjectIdWithUserAndRole(10L)).thenReturn(List.of(workerMember));

        CompanyScheduleView view = service.getProjection(new CompanyScheduleQuery(null, null, null, null, null));

        assertThat(view.rows()).isNotEmpty();
        assertNoWrites();
    }
}
