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
            if (p.getId() == null) {
                p.setId((long) (stored.size() + 100));
            }
            if (!stored.contains(p)) {
                stored.add(p);
            }
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

    // ------------------------------------------------------------------ move / swap (FOR-10 11.6)

    private UserDashboardWidgetEntity place(long id, DashboardWidgetEntity w, int cell, ProjectEntity project) {
        UserEntity u = new UserEntity();
        u.setId(USER);
        UserDashboardWidgetEntity p = new UserDashboardWidgetEntity();
        p.setId(id);
        p.setUser(u);
        p.setWidget(w);
        p.setCellIndex((short) cell);
        p.setProject(project);
        stored.add(p);
        when(placementDao.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    private UserDashboardWidgetEntity place(long id, DashboardWidgetEntity w, int cell) {
        return place(id, w, cell, null);
    }

    @Test
    void moveOkChangesOnlyCell() {
        DashboardWidgetEntity w = widget(1, 2, 1, "NONE", true);
        UserDashboardWidgetEntity p = place(200, w, 0);
        PlacementView v = service.movePlacement(USER, 200L, 6);
        assertThat(v.cellIndex()).isEqualTo(6);
        assertThat(p.getCellIndex()).isEqualTo((short) 6);
        assertThat(p.getWidget()).isSameAs(w);
        verify(placementDao).save(p);
    }

    @Test
    void moveRejectsInvalidAndOccupiedWithoutWrite() {
        DashboardWidgetEntity w2 = widget(1, 2, 1, "NONE", true);
        DashboardWidgetEntity w1 = widget(2, 1, 1, "NONE", true);
        UserDashboardWidgetEntity a = place(200, w2, 0);
        place(201, w1, 5);
        expect(() -> service.movePlacement(USER, 200L, 3), 400, "error.dashboard.cell.invalid");
        expect(() -> service.movePlacement(USER, 200L, 36), 400, "error.dashboard.cell.invalid");
        expect(() -> service.movePlacement(USER, 200L, -1), 400, "error.dashboard.cell.invalid");
        expect(() -> service.movePlacement(USER, 200L, 4), 409, "error.dashboard.cell.occupied");
        expect(() -> service.movePlacement(USER, 200L, 5), 409, "error.dashboard.cell.occupied");
        assertThat(a.getCellIndex()).isEqualTo((short) 0);
        verify(placementDao, org.mockito.Mockito.never()).save(any(UserDashboardWidgetEntity.class));
    }

    @Test
    void moveOverOwnOldFootprintIsAllowed() {
        UserDashboardWidgetEntity p = place(200, widget(1, 2, 1, "NONE", true), 0);
        assertThat(service.movePlacement(USER, 200L, 1).cellIndex()).isEqualTo(1);
        assertThat(p.getCellIndex()).isEqualTo((short) 1);
    }

    @Test
    void moveForeignAndMissingGiveIdentical403() {
        UserEntity other = new UserEntity();
        other.setId(8L);
        UserDashboardWidgetEntity foreign = new UserDashboardWidgetEntity();
        foreign.setId(300L);
        foreign.setUser(other);
        foreign.setWidget(widget(1, 1, 1, "NONE", true));
        foreign.setCellIndex((short) 0);
        when(placementDao.findById(300L)).thenReturn(Optional.of(foreign));
        when(placementDao.findById(301L)).thenReturn(Optional.empty());
        expect(() -> service.movePlacement(USER, 300L, 5), 403, "error.dashboard.forbidden");
        expect(() -> service.movePlacement(USER, 301L, 5), 403, "error.dashboard.forbidden");
        assertThat(foreign.getCellIndex()).isEqualTo((short) 0);
        verify(placementDao, org.mockito.Mockito.never()).save(any(UserDashboardWidgetEntity.class));
    }

    @Test
    void moveToCurrentCellIsIdempotentWithoutWrite() {
        UserDashboardWidgetEntity p = place(200, widget(1, 1, 1, "NONE", true), 4);
        assertThat(service.movePlacement(USER, 200L, 4).cellIndex()).isEqualTo(4);
        assertThat(p.getCellIndex()).isEqualTo((short) 4);
        verify(placementDao, org.mockito.Mockito.never()).save(any(UserDashboardWidgetEntity.class));
    }

    @Test
    void moveKeepsWidgetAndProjectIds() {
        DashboardWidgetEntity w = widget(1, 1, 1, "SINGLE_PROJECT", true);
        ProjectEntity project = new ProjectEntity();
        project.setId(10L);
        project.setName("P10");
        UserDashboardWidgetEntity p = place(200, w, 0, project);
        PlacementView v = service.movePlacement(USER, 200L, 9);
        assertThat(v.widgetId()).isEqualTo(1L);
        assertThat(v.projectId()).isEqualTo(10L);
        assertThat(p.getWidget().getId()).isEqualTo(1L);
        assertThat(p.getProject().getId()).isEqualTo(10L);
    }

    @Test
    void unavailablePlacementsStayMovable() {
        for (WidgetState state : new WidgetState[]{WidgetState.NO_ACCESS, WidgetState.INACTIVE,
                WidgetState.PROJECT_UNAVAILABLE}) {
            stored.clear();
            DashboardWidgetEntity w = widget(1, 1, 1, "NONE", state != WidgetState.INACTIVE);
            UserDashboardWidgetEntity p = place(200, w, 0);
            when(access.evaluate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), any())).thenReturn(state);
            when(access.missingResources(any(), any()))
                    .thenReturn(state == WidgetState.NO_ACCESS ? Set.of("WORKS") : Set.of());
            PlacementView v = service.movePlacement(USER, 200L, 7);
            assertThat(v.cellIndex()).isEqualTo(7);
            assertThat(v.state()).isEqualTo(state);
            assertThat(p.getCellIndex()).isEqualTo((short) 7);
        }
    }

    @Test
    void swapOkExchangesCells() {
        UserDashboardWidgetEntity a = place(200, widget(1, 1, 1, "NONE", true), 0);
        UserDashboardWidgetEntity b = place(201, widget(2, 2, 1, "NONE", true), 6);
        List<PlacementView> views = service.swapPlacements(USER, 200L, 201L);
        assertThat(views).extracting(PlacementView::id).containsExactly(200L, 201L);
        assertThat(views).extracting(PlacementView::cellIndex).containsExactly(6, 0);
        assertThat(a.getCellIndex()).isEqualTo((short) 6);
        assertThat(b.getCellIndex()).isEqualTo((short) 0);
        assertThat(a.getWidget().getId()).isEqualTo(1L);
        assertThat(b.getWidget().getId()).isEqualTo(2L);
    }

    @Test
    void swapInvalidGives400AndChangesNothing() {
        // wide widget at col 0 would land at col 3 -> out of grid
        UserDashboardWidgetEntity a = place(200, widget(1, 2, 1, "NONE", true), 0);
        UserDashboardWidgetEntity b = place(201, widget(2, 1, 1, "NONE", true), 3);
        expect(() -> service.swapPlacements(USER, 200L, 201L), 400, "error.dashboard.cell.invalid");
        assertThat(a.getCellIndex()).isEqualTo((short) 0);
        assertThat(b.getCellIndex()).isEqualTo((short) 3);
        verify(placementDao, org.mockito.Mockito.never()).save(any(UserDashboardWidgetEntity.class));
    }

    @Test
    void swapIntoThirdPlacementGives409AndChangesNothing() {
        UserDashboardWidgetEntity a = place(200, widget(1, 2, 1, "NONE", true), 0);
        UserDashboardWidgetEntity b = place(201, widget(2, 1, 1, "NONE", true), 8);
        place(202, widget(3, 1, 1, "NONE", true), 9);
        // a would land on 8-9, cell 9 is held by the third placement
        expect(() -> service.swapPlacements(USER, 200L, 201L), 409, "error.dashboard.cell.occupied");
        assertThat(a.getCellIndex()).isEqualTo((short) 0);
        assertThat(b.getCellIndex()).isEqualTo((short) 8);
        verify(placementDao, org.mockito.Mockito.never()).save(any(UserDashboardWidgetEntity.class));
    }

    @Test
    void swapMutualOverlapGives409() {
        // a 3x1 at 0 (0,1,2) and b 1x3 at 4 (4,8,12) are disjoint now; after the swap a' = (4,5,6) and
        // b' = (0,4,8) both fit the empty rest of the grid but share cell 4.
        UserDashboardWidgetEntity a = place(200, widget(1, 3, 1, "NONE", true), 0);
        UserDashboardWidgetEntity b = place(201, widget(2, 1, 3, "NONE", true), 4);
        expect(() -> service.swapPlacements(USER, 200L, 201L), 409, "error.dashboard.cell.occupied");
        assertThat(a.getCellIndex()).isEqualTo((short) 0);
        assertThat(b.getCellIndex()).isEqualTo((short) 4);
        verify(placementDao, org.mockito.Mockito.never()).save(any(UserDashboardWidgetEntity.class));
    }

    @Test
    void swapSameIdGives400() {
        place(200, widget(1, 1, 1, "NONE", true), 0);
        expect(() -> service.swapPlacements(USER, 200L, 200L), 400, "error.dashboard.cell.invalid");
    }

    @Test
    void swapForeignOrMissingGives403BeforeGeometry() {
        place(200, widget(1, 1, 1, "NONE", true), 0);
        UserEntity other = new UserEntity();
        other.setId(8L);
        UserDashboardWidgetEntity foreign = new UserDashboardWidgetEntity();
        foreign.setId(300L);
        foreign.setUser(other);
        when(placementDao.findById(300L)).thenReturn(Optional.of(foreign));
        when(placementDao.findById(301L)).thenReturn(Optional.empty());
        expect(() -> service.swapPlacements(USER, 200L, 300L), 403, "error.dashboard.forbidden");
        expect(() -> service.swapPlacements(USER, 200L, 301L), 403, "error.dashboard.forbidden");
        expect(() -> service.swapPlacements(USER, 301L, 200L), 403, "error.dashboard.forbidden");
        expect(() -> service.swapPlacements(USER, 300L, 300L), 403, "error.dashboard.forbidden");
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
