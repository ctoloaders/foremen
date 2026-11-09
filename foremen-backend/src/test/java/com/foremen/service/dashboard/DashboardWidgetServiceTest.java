package com.foremen.service.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.foremen.dao.DashboardWidgetDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDashboardWidgetDao;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.dashboard.DashboardWidgetService.WidgetCommand;
import com.foremen.service.dashboard.config.WidgetConfig;
import com.foremen.service.dashboard.config.WidgetConfigValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DashboardWidgetService}.
 * <b>Validates: Requirements 4.3-4.9, 5.6, 8.7, 11.2-11.4, 18.4</b>
 */
class DashboardWidgetServiceTest {

    private final DashboardWidgetDao widgetDao = mock(DashboardWidgetDao.class);
    private final UserDashboardWidgetDao placementDao = mock(UserDashboardWidgetDao.class);
    private final RoleDao roleDao = mock(RoleDao.class);
    private final DashboardAccessService access = mock(DashboardAccessService.class);
    private final WidgetConfigValidator validator = mock(WidgetConfigValidator.class);
    private final AuditLogDao auditDao = mock(AuditLogDao.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private DashboardWidgetService service;

    @BeforeEach
    void setUp() {
        WidgetResourceResolver resolver = w -> Set.of("PROJECTS");
        service = new DashboardWidgetService(widgetDao, placementDao, roleDao, access, resolver, validator,
                auditDao, events);
        when(widgetDao.save(any())).thenAnswer(i -> {
            DashboardWidgetEntity e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(10L);
            }
            return e;
        });
        when(roleDao.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(role("FOREMAN"), role("ADMIN"))));
        when(access.missingResources("ADMIN", Set.of("PROJECTS"))).thenReturn(Set.of());
        when(access.missingResources("FOREMAN", Set.of("PROJECTS"))).thenReturn(Set.of("PROJECTS"));
        validatorReturns(new WidgetConfigValidator.Result(typed(), List.of()));
    }

    private void validatorReturns(WidgetConfigValidator.Result result) {
        when(validator.validate(any(JsonNode.class), anyBoolean())).thenReturn(result);
    }

    private static RoleEntity role(String code) {
        RoleEntity r = new RoleEntity();
        r.setCode(code);
        return r;
    }

    private static DashboardWidgetEntity entity(long id, boolean active, boolean seed) {
        DashboardWidgetEntity w = new DashboardWidgetEntity();
        w.setId(id);
        w.setCode("W_" + id + "_X");
        w.setNameRu("n");
        w.setNamePl("n");
        w.setActive(active);
        w.setSystemSeed(seed);
        w.setColSpan((short) 1);
        w.setRowSpan((short) 1);
        w.setProjectScope("NONE");
        w.setConfig(Map.of("dataset", "PROJECTS", "visualization", "TABLE", "schemaVersion", 1));
        return w;
    }

    private static WidgetCommand command(String code) {
        return new WidgetCommand(code, "ru", "pl", " ", null,
                Map.of("schemaVersion", 1, "dataset", "PROJECTS", "visualization", "KPI"), 2, 1, "NONE");
    }

    private static WidgetConfig typed() {
        return new WidgetConfig(1, "PROJECTS", null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    @Test
    void createRejectsDuplicateCode() {
        when(widgetDao.existsByCode("NEW_WIDGET")).thenReturn(true);
        assertThatThrownBy(() -> service.create(command("NEW_WIDGET")))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getMessageCode()).isEqualTo("error.dashboard.widget.code.exists");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(widgetDao, never()).save(any());
    }

    @Test
    void createRejectsInvalidConfigWithViolations() {
        validatorReturns(new WidgetConfigValidator.Result(null,
                List.of(new WidgetConfigValidator.Violation("$.dataset", "unknown", "x"))));
        assertThatThrownBy(() -> service.create(command("NEW_WIDGET")))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getMessageCode()).isEqualTo("error.dashboard.config.invalid");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getParams()).containsKey("violations");
                });
    }

    @Test
    void createRejectsBadCodePattern() {
        assertThatThrownBy(() -> service.create(command("bad")))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("error.dashboard.config.invalid"));
    }

    @Test
    void createPersistsAuditsAndComputesVisibleRoles() {
        var view = service.create(command("NEW_WIDGET"));
        assertThat(view.id()).isEqualTo(10L);
        assertThat(view.active()).isTrue();
        assertThat(view.systemSeed()).isFalse();
        assertThat(view.descriptionRu()).isNull();
        assertThat(view.requiredResources()).containsExactly("PROJECTS");
        assertThat(view.visibleToRoles()).containsExactly("ADMIN");
        ArgumentCaptor<AuditLogEntity> cap = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditDao).save(cap.capture());
        assertThat(cap.getValue().getOperation()).isEqualTo("CREATE");
        assertThat(cap.getValue().getEntityClass()).isEqualTo("DashboardWidgetEntity");
    }

    @Test
    void updateKeepsCode() {
        when(widgetDao.findById(3L)).thenReturn(Optional.of(entity(3, true, false)));
        var view = service.update(3L, command("OTHER_CODE"));
        assertThat(view.code()).isEqualTo("W_3_X");
        assertThat(view.colSpan()).isEqualTo(2);
    }

    @Test
    void deactivatePublishesEventOnlyOnTransition() {
        DashboardWidgetEntity w = entity(4, true, false);
        when(widgetDao.findById(4L)).thenReturn(Optional.of(w));
        service.deactivate(4L);
        verify(events).publishEvent(new DashboardWidgetDeactivatedEvent(4L));
        assertThat(w.getActive()).isFalse();
        service.deactivate(4L);
        verify(events, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void activateReactivatesWithoutEvent() {
        when(widgetDao.findById(5L)).thenReturn(Optional.of(entity(5, false, false)));
        assertThat(service.activate(5L).active()).isTrue();
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void deleteBlockedWhenInUse() {
        when(widgetDao.findById(6L)).thenReturn(Optional.of(entity(6, true, false)));
        when(placementDao.existsByWidgetId(6L)).thenReturn(true);
        assertThatThrownBy(() -> service.delete(6L)).isInstanceOfSatisfying(ForemenApiException.class, e -> {
            assertThat(e.getMessageCode()).isEqualTo("error.dashboard.widget.in.use");
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
    }

    @Test
    void deleteBlockedForSystemSeed() {
        when(widgetDao.findById(7L)).thenReturn(Optional.of(entity(7, true, true)));
        assertThatThrownBy(() -> service.delete(7L)).isInstanceOf(ForemenApiException.class);
        verify(widgetDao, never()).delete(any(DashboardWidgetEntity.class));
    }

    @Test
    void deleteUnusedWidgetAudits() {
        DashboardWidgetEntity w = entity(8, true, false);
        when(widgetDao.findById(8L)).thenReturn(Optional.of(w));
        service.delete(8L);
        verify(widgetDao).delete(w);
        ArgumentCaptor<AuditLogEntity> cap = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditDao).save(cap.capture());
        assertThat(cap.getValue().getOperation()).isEqualTo("DELETE");
        assertThat(cap.getValue().getSnapshotAfter()).isNull();
    }

    @Test
    void getUnknownIs404() {
        when(widgetDao.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(99L)).isInstanceOfSatisfying(ForemenApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listMapsPageWithInUseFlag() {
        DashboardWidgetEntity w = entity(1, true, true);
        when(placementDao.existsByWidgetId(1L)).thenReturn(true);
        when(widgetDao.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(w), PageRequest.of(0, 20), 1));
        var page = service.list(PageRequest.of(0, 20), "kpi", true);
        assertThat(page.getContent()).singleElement().satisfies(v -> {
            assertThat(v.inUse()).isTrue();
            assertThat(v.visualization()).isEqualTo("TABLE");
            assertThat(v.dataset()).isEqualTo("PROJECTS");
        });
    }
}
