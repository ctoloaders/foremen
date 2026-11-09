package com.foremen.service.dashboard;

import com.foremen.dao.DashboardWidgetDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.UserDashboardWidgetDao;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserDashboardWidgetEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DashboardLayoutService}.
 * <b>Validates: Requirements 3.3, 3.6, 3.7, 9.3, 12.1, 12.2</b>
 */
class DashboardLayoutServiceTest {

    private static final Long USER = 7L;

    private final UserDashboardWidgetDao placementDao = mock(UserDashboardWidgetDao.class);
    private final DashboardWidgetDao widgetDao = mock(DashboardWidgetDao.class);
    private final UserDao userDao = mock(UserDao.class);
    private final ProjectDao projectDao = mock(ProjectDao.class);
    private final ProjectAccessCache projectCache = mock(ProjectAccessCache.class);
    private final DashboardAccessService access = mock(DashboardAccessService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<WidgetResourceResolver> resolverProvider = mock(ObjectProvider.class);
    private final EntityManager em = mock(EntityManager.class);
    private final Query lockQuery = mock(Query.class);
    private final List<UserDashboardWidgetEntity> stored = new ArrayList<>();
    private DashboardLayoutService service;

    @BeforeEach
    void setUp() {
        service = new DashboardLayoutService(placementDao, widgetDao, userDao, projectDao, projectCache, access,
                resolverProvider);
        ReflectionTestUtils.setField(service, "entityManager", em);
        when(em.createNativeQuery(any(String.class))).thenReturn(lockQuery);
        when(lockQuery.setParameter(any(String.class), any())).thenReturn(lockQuery);
        when(resolverProvider.getIfAvailable()).thenReturn(w -> Set.of("WORKS"));
        RoleEntity role = new RoleEntity();
        role.setCode("MANAGER");
        UserEntity user = new UserEntity();
        user.setId(USER);
        user.setRole(role);
        when(userDao.findById(USER)).thenReturn(Optional.of(user));
        when(placementDao.findByUserIdOrdered(USER)).thenAnswer(i -> new ArrayList<>(stored));
        when(placementDao.save(any(UserDashboardWidgetEntity.class))).thenAnswer(i -> {
            UserDashboardWidgetEntity p = i.getArgument(0);
            p.setId((long) (stored.size() + 100));
            stored.add(p);
            return p;
        });
        when(access.missingResources(any(), any())).thenReturn(Set.of());
        when(access.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any())).thenReturn(WidgetState.AVAILABLE);
    }

    private DashboardWidgetEntity widget(long id, int cs, int rs, String scope, boolean active) {
        DashboardWidgetEntity w = new DashboardWidgetEntity();
        w.setId(id);
        w.setCode("W" + id);
        w.setNameRu("ru");
        w.setNamePl("pl");
        w.setColSpan((short) cs);
        w.setRowSpan((short) rs);
        w.setProjectScope(scope);
        w.setActive(active);
        w.setConfig(Map.of("visualization", "KPI", "dateField", "createdDate"));
        when(widgetDao.findById(id)).thenReturn(Optional.of(w));
        return w;
    }

    private static void expect(Runnable r, int status, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ForemenApiException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(status);
            assertThat(e.getMessageCode()).isEqualTo(code);
        });
    }

    @Test
    void firstFitPlacementAndLock() {
        widget(1, 2, 2, "NONE", true);
        widget(2, 1, 1, "NONE", true);
        assertThat(service.addPlacement(USER, 1L, null, null).cellIndex()).isZero();
        assertThat(service.addPlacement(USER, 2L, null, null).cellIndex()).isEqualTo(2);
        verify(lockQuery, org.mockito.Mockito.times(2)).getSingleResult();
    }

    @Test
    void explicitCellRules() {
        widget(1, 2, 1, "NONE", true);
        widget(2, 1, 1, "NONE", true);
        expect(() -> service.addPlacement(USER, 1L, 3, null), 400, "error.dashboard.cell.invalid");
        service.addPlacement(USER, 1L, 0, null);
        expect(() -> service.addPlacement(USER, 2L, 1, null), 409, "error.dashboard.cell.occupied");
        assertThat(service.addPlacement(USER, 2L, 2, null).cellIndex()).isEqualTo(2);
    }

    @Test
    void gridFullAndDuplicate() {
        widget(1, 4, 9, "NONE", true);
        widget(2, 1, 1, "NONE", true);
        service.addPlacement(USER, 1L, null, null);
        expect(() -> service.addPlacement(USER, 1L, null, null), 409, "error.dashboard.widget.duplicate");
        expect(() -> service.addPlacement(USER, 2L, null, null), 409, "error.dashboard.grid.full");
    }

    @Test
    void inactiveAndNoAccess() {
        widget(1, 1, 1, "NONE", false);
        expect(() -> service.addPlacement(USER, 1L, null, null), 409, "error.dashboard.widget.inactive");
        widget(2, 1, 1, "NONE", true);
        when(access.missingResources(any(), any())).thenReturn(Set.of("WORKS"));
        expect(() -> service.addPlacement(USER, 2L, null, null), 403, "error.dashboard.widget.disabled");
    }

    @Test
    void projectWidgetRules() {
        widget(1, 1, 1, "SINGLE_PROJECT", true);
        widget(2, 1, 1, "NONE", true);
        when(projectCache.get(USER)).thenReturn(Set.of(10L, 11L));
        for (long id : new long[]{10L, 11L}) {
            ProjectEntity p = new ProjectEntity();
            p.setId(id);
            p.setName("P" + id);
            when(projectDao.findById(id)).thenReturn(Optional.of(p));
        }
        expect(() -> service.addPlacement(USER, 1L, null, null), 400, "error.dashboard.project.required");
        expect(() -> service.addPlacement(USER, 1L, null, 99L), 403, "error.dashboard.project.forbidden");
        expect(() -> service.addPlacement(USER, 2L, null, 10L), 400, "error.dashboard.project.unexpected");
        PlacementView v = service.addPlacement(USER, 1L, null, 10L);
        assertThat(v.projectId()).isEqualTo(10L);
        assertThat(v.projectName()).isEqualTo("P10");
        assertThat(service.addPlacement(USER, 1L, null, 11L).cellIndex()).isEqualTo(1);
        expect(() -> service.addPlacement(USER, 1L, null, 10L), 409, "error.dashboard.widget.duplicate");
    }

    @Test
    void readReturnsStatesInOrderAndRemoveOwnership() {
        widget(1, 1, 1, "NONE", true);
        service.addPlacement(USER, 1L, null, null);
        when(access.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any())).thenReturn(WidgetState.NO_ACCESS);
        when(access.missingResources(any(), any())).thenReturn(Set.of("WORKS"));
        List<PlacementView> view = service.read(USER);
        assertThat(view).hasSize(1);
        assertThat(view.get(0).state()).isEqualTo(WidgetState.NO_ACCESS);
        assertThat(view.get(0).missingResources()).containsExactly("WORKS");
        assertThat(view.get(0).visualization()).isEqualTo("KPI");
        assertThat(view.get(0).dateAware()).isTrue();

        UserEntity other = new UserEntity();
        other.setId(8L);
        UserDashboardWidgetEntity foreign = new UserDashboardWidgetEntity();
        foreign.setUser(other);
        when(placementDao.findById(500L)).thenReturn(Optional.of(foreign));
        expect(() -> service.removePlacement(USER, 500L), 403, "error.dashboard.forbidden");
        expect(() -> service.removePlacement(USER, 501L), 404, "error.dashboard.placement.notFound");
        when(placementDao.findById(stored.get(0).getId())).thenReturn(Optional.of(stored.get(0)));
        service.removePlacement(USER, stored.get(0).getId());
        verify(placementDao).delete(stored.get(0));
    }
}
