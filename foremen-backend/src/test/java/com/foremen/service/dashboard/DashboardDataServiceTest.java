package com.foremen.service.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.foremen.dao.UserDao;
import com.foremen.dao.UserDashboardWidgetDao;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserDashboardWidgetEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.dashboard.config.WidgetConfig;
import com.foremen.service.dashboard.config.WidgetConfigValidator;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.DatasetRegistry;
import com.foremen.service.dashboard.query.DashboardQueryEngine;
import com.foremen.service.dashboard.query.DashboardQueryTimeoutException;
import com.foremen.service.dashboard.query.QueryContext;
import com.foremen.service.dashboard.query.WidgetResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DashboardDataService}: the ordered fetch pipeline and date validation.
 * <b>Validates: Requirements 2.5, 6.3, 6.5, 6.7, 9.5, 12.4</b>
 */
class DashboardDataServiceTest {

    private static final Long USER = 7L;
    private static final Long PLACEMENT = 11L;
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 1, 31);

    private final UserDashboardWidgetDao placementDao = mock(UserDashboardWidgetDao.class);
    private final UserDao userDao = mock(UserDao.class);
    private final DashboardAccessService access = mock(DashboardAccessService.class);
    private final ProjectAccessCache projectCache = mock(ProjectAccessCache.class);
    private final DatasetRegistry registry = mock(DatasetRegistry.class);
    private final WidgetConfigValidator validator = mock(WidgetConfigValidator.class);
    private final DashboardQueryEngine engine = mock(DashboardQueryEngine.class);
    private final Dataset dataset = mock(Dataset.class);
    private final WidgetConfig parsed = new WidgetConfig(1, "PROJECTS", WidgetConfig.Visualization.TABLE,
            null, null, null, null, null, null, null, null, null, null, null);

    private DashboardDataService service;
    private UserDashboardWidgetEntity placement;
    private DashboardWidgetEntity widget;

    @BeforeEach
    void setUp() {
        service = new DashboardDataService(placementDao, userDao, access, projectCache, registry, validator,
                engine);
        RoleEntity role = new RoleEntity();
        role.setCode("MANAGER");
        UserEntity user = new UserEntity();
        user.setId(USER);
        user.setRole(role);
        when(userDao.findById(USER)).thenReturn(Optional.of(user));
        widget = new DashboardWidgetEntity();
        widget.setId(3L);
        widget.setActive(true);
        widget.setProjectScope("NONE");
        widget.setConfig(Map.of("dataset", "PROJECTS"));
        placement = new UserDashboardWidgetEntity();
        placement.setId(PLACEMENT);
        placement.setUser(user);
        placement.setWidget(widget);
        when(placementDao.findById(PLACEMENT)).thenReturn(Optional.of(placement));
        when(registry.get("PROJECTS")).thenReturn(Optional.of(dataset));
        when(dataset.requiredResources()).thenReturn(Set.of("PROJECTS"));
        when(dataset.code()).thenReturn("PROJECTS");
        when(projectCache.get(USER)).thenReturn(Set.of(1L, 2L));
        stateIs(WidgetState.AVAILABLE);
        when(validator.validate(any(JsonNode.class), anyBoolean()))
                .thenReturn(new WidgetConfigValidator.Result(parsed, List.of()));
    }

    private void stateIs(WidgetState s) {
        when(access.evaluate(any(), any(), anyBoolean(), any(), anyBoolean(), any())).thenReturn(s);
    }

    private static WidgetResult result() {
        return new WidgetResult(List.of(), List.of(), null, null, false, Instant.now());
    }

    @Test
    void fetchRunsQueryWithCallerScopeAndDates() {
        when(engine.execute(any(), any(), any())).thenReturn(result());

        assertThat(service.fetch(USER, PLACEMENT, FROM, TO).truncated()).isFalse();

        ArgumentCaptor<QueryContext> ctx = ArgumentCaptor.forClass(QueryContext.class);
        verify(engine).execute(any(), any(), ctx.capture());
        assertThat(ctx.getValue().userId()).isEqualTo(USER);
        assertThat(ctx.getValue().admin()).isFalse();
        assertThat(ctx.getValue().allowedProjectIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(ctx.getValue().dateFrom()).isEqualTo(FROM);
        assertThat(ctx.getValue().dateTo()).isEqualTo(TO);
        assertThat(ctx.getValue().boundProjectId()).isNull();
    }

    @Test
    void missingPlacementIs404AndForeignIs403() {
        assertThatThrownBy(() -> service.fetch(USER, 999L, FROM, TO))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(404));
        UserEntity other = new UserEntity();
        other.setId(99L);
        placement.setUser(other);
        assertThatThrownBy(() -> service.fetch(USER, PLACEMENT, FROM, TO))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(403);
                    assertThat(e.getMessageCode()).isEqualTo("error.dashboard.forbidden");
                });
        verify(engine, never()).execute(any(), any(), any());
    }

    @Test
    void nonAvailableStateIs403WithReasonAndNeverQueries() {
        for (WidgetState s : List.of(WidgetState.INACTIVE, WidgetState.NO_ACCESS, WidgetState.PROJECT_UNAVAILABLE)) {
            stateIs(s);
            assertThatThrownBy(() -> service.fetch(USER, PLACEMENT, FROM, TO))
                    .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                        assertThat(e.getStatus().value()).isEqualTo(403);
                        assertThat(e.getMessageCode()).isEqualTo("error.dashboard.widget.disabled");
                        assertThat(e.getParams()).containsEntry("reason", s.name());
                    });
        }
        verify(engine, never()).execute(any(), any(), any());
    }

    @Test
    void projectWidgetUsesStoredProjectOnly() {
        widget.setProjectScope("SINGLE_PROJECT");
        ProjectEntity p = new ProjectEntity();
        p.setId(2L);
        placement.setProject(p);
        when(engine.execute(any(), any(), any())).thenReturn(result());

        service.fetch(USER, PLACEMENT, FROM, TO);

        ArgumentCaptor<QueryContext> ctx = ArgumentCaptor.forClass(QueryContext.class);
        verify(engine).execute(any(), any(), ctx.capture());
        assertThat(ctx.getValue().boundProjectId()).isEqualTo(2L);
    }

    @Test
    void projectWidgetOutsideAccessIsProjectUnavailable() {
        widget.setProjectScope("SINGLE_PROJECT");
        ProjectEntity p = new ProjectEntity();
        p.setId(500L);
        placement.setProject(p);

        assertThatThrownBy(() -> service.fetch(USER, PLACEMENT, FROM, TO))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getParams()).containsEntry("reason", "PROJECT_UNAVAILABLE"));
        verify(engine, never()).execute(any(), any(), any());
    }

    @Test
    void timeoutReturnsTruncatedEmptyResult() {
        when(engine.execute(any(), any(), any()))
                .thenThrow(new DashboardQueryTimeoutException(new RuntimeException("timeout")));

        WidgetResult r = service.fetch(USER, PLACEMENT, FROM, TO);

        assertThat(r.truncated()).isTrue();
        assertThat(r.rows()).isEmpty();
    }

    @Test
    void invalidStoredConfigIs422() {
        when(validator.validate(any(JsonNode.class), anyBoolean()))
                .thenReturn(new WidgetConfigValidator.Result(null,
                        List.of(new WidgetConfigValidator.Violation("$", "x", "bad"))));

        assertThatThrownBy(() -> service.fetch(USER, PLACEMENT, FROM, TO))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(422));
        verify(engine, never()).execute(any(), any(), any());
    }

    @Test
    void dateValidation() {
        DashboardDataService.validateRange(FROM, FROM);
        DashboardDataService.validateRange(FROM, FROM.plusYears(5));
        DashboardDataService.validateRange(null, null);
        LocalDate[][] invalid = {{TO, FROM}, {FROM, FROM.plusYears(5).plusDays(1)}, {FROM, null}, {null, TO}};
        for (LocalDate[] r : invalid) {
            assertThatThrownBy(() -> DashboardDataService.validateRange(r[0], r[1]))
                    .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                        assertThat(e.getStatus().value()).isEqualTo(400);
                        assertThat(e.getMessageCode()).isEqualTo("error.dashboard.date.invalid");
                    });
        }
    }

    @Test
    void invalidDatesRejectedBeforeQuery() {
        assertThatThrownBy(() -> service.fetch(USER, PLACEMENT, TO, FROM)).isInstanceOf(ForemenApiException.class);
        verify(engine, never()).execute(any(), any(), any());
    }

    @Test
    void previewRunsAsCallerAndChecksAccess() {
        when(engine.execute(any(), any(), any())).thenReturn(result());
        assertThat(service.previewFetch(USER, Map.of("dataset", "PROJECTS"), false, null, FROM, TO)).isNotNull();

        stateIs(WidgetState.NO_ACCESS);
        assertThatThrownBy(() -> service.previewFetch(USER, Map.of("dataset", "PROJECTS"), false, null, FROM, TO))
                .isInstanceOf(ForemenApiException.class);
    }

    @Test
    void unknownDatasetIs422() {
        assertThatThrownBy(() -> service.previewFetch(USER, Map.of("dataset", "NOPE"), false, null, null, null))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("error.dashboard.config.invalid"));
    }
}
