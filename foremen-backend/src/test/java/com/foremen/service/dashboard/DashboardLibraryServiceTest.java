package com.foremen.service.dashboard;

import com.foremen.dao.DashboardWidgetDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.ProjectAccessCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DashboardLibraryService}.
 * <b>Validates: Requirements 9.1, 12.2</b>
 */
class DashboardLibraryServiceTest {

    private static final Long USER = 5L;

    private final DashboardWidgetDao widgetDao = mock(DashboardWidgetDao.class);
    private final UserDao userDao = mock(UserDao.class);
    private final DashboardAccessService access = mock(DashboardAccessService.class);
    private final ProjectDao projectDao = mock(ProjectDao.class);
    private final ProjectAccessCache projectCache = mock(ProjectAccessCache.class);
    private final RoleEntity role = new RoleEntity();
    private DashboardLibraryService service;

    @BeforeEach
    void setUp() {
        WidgetResourceResolver resolver = w -> Set.of(String.valueOf(w.getConfig().get("dataset")));
        service = new DashboardLibraryService(widgetDao, userDao, access, resolver, projectDao, projectCache);
        role.setCode("FOREMAN");
        UserEntity user = new UserEntity();
        user.setId(USER);
        user.setRole(role);
        when(userDao.findById(USER)).thenReturn(Optional.of(user));
    }

    private static DashboardWidgetEntity widget(long id, String dataset, boolean active) {
        DashboardWidgetEntity w = new DashboardWidgetEntity();
        w.setId(id);
        w.setCode("W" + id + "_X");
        w.setActive(active);
        w.setColSpan((short) 2);
        w.setRowSpan((short) 1);
        w.setConfig(Map.of("dataset", dataset, "visualization", "TABLE"));
        return w;
    }

    private static ProjectEntity project(long id, String name, ProjectStatus status) {
        ProjectEntity p = new ProjectEntity();
        p.setId(id);
        p.setName(name);
        p.setAddress("ul. " + name);
        p.setStatus(status);
        return p;
    }

    @Test
    void libraryListsActiveWidgetsWithAvailability() {
        when(widgetDao.findAll()).thenReturn(List.of(widget(2, "PAYROLL", true), widget(1, "PROJECTS", true),
                widget(3, "PROJECTS", false)));
        when(access.missingResources(eq("FOREMAN"), eq(Set.of("PROJECTS")))).thenReturn(Set.of());
        when(access.missingResources(eq("FOREMAN"), eq(Set.of("PAYROLL")))).thenReturn(Set.of("PAYROLL"));

        List<DashboardLibraryService.LibraryItem> items = service.library(USER);

        assertThat(items).extracting(DashboardLibraryService.LibraryItem::id).containsExactly(1L, 2L);
        assertThat(items.get(0).available()).isTrue();
        assertThat(items.get(0).unavailableReason()).isNull();
        assertThat(items.get(0).visualization()).isEqualTo("TABLE");
        assertThat(items.get(1).available()).isFalse();
        assertThat(items.get(1).unavailableReason()).isEqualTo("NO_ACCESS");
        assertThat(items.get(1).missingResources()).containsExactly("PAYROLL");
    }

    @Test
    void projectPickerUsesAccessibleActiveProjectsAndSearch() {
        when(projectCache.get(USER)).thenReturn(Set.of(1L, 2L, 3L));
        when(projectDao.findAllById(any())).thenReturn(List.of(project(1, "Beta", ProjectStatus.ACTIVE),
                project(2, "Alpha", ProjectStatus.ACTIVE), project(3, "Gamma", ProjectStatus.DRAFT)));

        assertThat(service.projects(USER, null, false))
                .extracting(DashboardLibraryService.ProjectOption::name).containsExactly("Alpha", "Beta");
        assertThat(service.projects(USER, null, true)).hasSize(3);
        assertThat(service.projects(USER, "bet", false))
                .extracting(DashboardLibraryService.ProjectOption::id).containsExactly(1L);
    }

    @Test
    void adminPickerSeesAllProjects() {
        role.setCode("ADMIN");
        when(projectDao.findAll()).thenReturn(List.of(project(9, "Any", ProjectStatus.ACTIVE)));

        assertThat(service.projects(USER, "", false)).extracting(DashboardLibraryService.ProjectOption::id)
                .containsExactly(9L);
    }
}
