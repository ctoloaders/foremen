package com.foremen.service.dashboard;

import com.foremen.dao.RoleDefaultWidgetDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.UserDashboardStateDao;
import com.foremen.dao.UserDashboardWidgetDao;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.RoleDefaultWidgetEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserDashboardStateEntity;
import com.foremen.dao.model.UserDashboardWidgetEntity;
import com.foremen.dao.model.UserEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DashboardDefaultsService}, {@link UserCreatedDashboardListener} and the lazy
 * seeding hook of {@link DashboardLayoutService#read}.
 * <b>Validates: Requirements 15.3, 15.4</b>
 */
class DashboardDefaultsServiceTest {

    private static final Long USER = 7L;
    private static final Long ROLE = 3L;

    private final UserDao userDao = mock(UserDao.class);
    private final RoleDefaultWidgetDao roleDefaultDao = mock(RoleDefaultWidgetDao.class);
    private final UserDashboardWidgetDao placementDao = mock(UserDashboardWidgetDao.class);
    private final UserDashboardStateDao stateDao = mock(UserDashboardStateDao.class);
    private final EntityManager em = mock(EntityManager.class);
    private final Query lockQuery = mock(Query.class);
    private DashboardDefaultsService service;

    @BeforeEach
    void setUp() {
        service = new DashboardDefaultsService(userDao, roleDefaultDao, placementDao, stateDao);
        ReflectionTestUtils.setField(service, "entityManager", em);
        when(em.createNativeQuery(any(String.class))).thenReturn(lockQuery);
        when(lockQuery.setParameter(any(String.class), any())).thenReturn(lockQuery);
        RoleEntity role = new RoleEntity();
        role.setId(ROLE);
        role.setCode("MANAGER");
        UserEntity user = new UserEntity();
        user.setId(USER);
        user.setRole(role);
        when(userDao.findById(USER)).thenReturn(Optional.of(user));
    }

    private static RoleDefaultWidgetEntity def(long widgetId, int cs, int rs, int cell, boolean active, String scope) {
        DashboardWidgetEntity w = new DashboardWidgetEntity();
        w.setId(widgetId);
        w.setColSpan((short) cs);
        w.setRowSpan((short) rs);
        w.setActive(active);
        w.setProjectScope(scope);
        RoleDefaultWidgetEntity d = new RoleDefaultWidgetEntity();
        d.setRoleId(ROLE);
        d.setCellIndex((short) cell);
        d.setWidget(w);
        return d;
    }

    @Test
    void copiesRoleDefaultsLocksAndWritesStateRow() {
        when(roleDefaultDao.findByRoleIdOrdered(ROLE)).thenReturn(List.of(
                def(1, 2, 2, 0, true, "NONE"), def(2, 1, 1, 2, true, "NONE")));

        service.seedFor(USER);

        ArgumentCaptor<UserDashboardWidgetEntity> cap = ArgumentCaptor.forClass(UserDashboardWidgetEntity.class);
        verify(placementDao, times(2)).save(cap.capture());
        assertThat(cap.getAllValues()).extracting(p -> (int) p.getCellIndex()).containsExactly(0, 2);
        verify(lockQuery).getSingleResult();
        ArgumentCaptor<UserDashboardStateEntity> st = ArgumentCaptor.forClass(UserDashboardStateEntity.class);
        verify(stateDao).save(st.capture());
        assertThat(st.getValue().getUserId()).isEqualTo(USER);
        assertThat(st.getValue().getInitializedAt()).isNotNull();
    }

    @Test
    void skipsInactiveProjectScopedAndOverlappingDefaults() {
        when(roleDefaultDao.findByRoleIdOrdered(ROLE)).thenReturn(List.of(
                def(1, 2, 2, 0, true, "NONE"),
                def(2, 1, 1, 1, true, "NONE"),               // overlaps widget 1
                def(3, 1, 1, 6, false, "NONE"),              // inactive
                def(4, 1, 1, 7, true, "SINGLE_PROJECT")));   // needs a project

        service.seedFor(USER);

        verify(placementDao, times(1)).save(any(UserDashboardWidgetEntity.class));
        verify(stateDao).save(any(UserDashboardStateEntity.class));
    }

    @Test
    void roleWithoutDefaultsStillGetsStateRow() {
        when(roleDefaultDao.findByRoleIdOrdered(ROLE)).thenReturn(List.of());

        service.seedFor(USER);

        verify(placementDao, never()).save(any(UserDashboardWidgetEntity.class));
        verify(stateDao).save(any(UserDashboardStateEntity.class));
    }

    @Test
    void isIdempotentWhenStateRowExists() {
        when(stateDao.existsById(USER)).thenReturn(true);

        service.seedFor(USER);

        verify(lockQuery, never()).getSingleResult();
        verify(placementDao, never()).save(any(UserDashboardWidgetEntity.class));
        verify(stateDao, never()).save(any(UserDashboardStateEntity.class));
    }

    @Test
    void secondCheckUnderLockPreventsDoubleSeeding() {
        when(stateDao.existsById(USER)).thenReturn(false, true);

        service.seedFor(USER);

        verify(lockQuery).getSingleResult();
        verify(placementDao, never()).save(any(UserDashboardWidgetEntity.class));
    }

    @Test
    void listenerSwallowsSeedingFailure() {
        DashboardDefaultsService defaults = mock(DashboardDefaultsService.class);
        doThrow(new IllegalStateException("boom")).when(defaults).seedFor(USER);

        new UserCreatedDashboardListener(defaults).onUserCreated(new UserCreatedEvent(USER));

        verify(defaults).seedFor(USER);
    }

    @Test
    void listenerDelegatesToSeedFor() {
        DashboardDefaultsService defaults = mock(DashboardDefaultsService.class);

        new UserCreatedDashboardListener(defaults).onUserCreated(new UserCreatedEvent(USER));

        verify(defaults).seedFor(USER);
    }

    @Test
    void layoutReadLazilySeedsBeforeListing() {
        UserDashboardWidgetDao pd = mock(UserDashboardWidgetDao.class);
        UserDao ud = mock(UserDao.class);
        DashboardLayoutService layout = new DashboardLayoutService(pd, null, ud, null, null, null, null);
        DashboardDefaultsService defaults = mock(DashboardDefaultsService.class);
        ReflectionTestUtils.setField(layout, "defaultsService", defaults);
        RoleEntity role = new RoleEntity();
        role.setCode("MANAGER");
        UserEntity user = new UserEntity();
        user.setId(USER);
        user.setRole(role);
        when(ud.findById(USER)).thenReturn(Optional.of(user));
        when(pd.findByUserIdOrdered(USER)).thenReturn(List.of());

        assertThat(layout.read(USER)).isEmpty();

        var order = org.mockito.Mockito.inOrder(defaults, pd);
        order.verify(defaults).seedFor(USER);
        order.verify(pd).findByUserIdOrdered(USER);
    }
}
