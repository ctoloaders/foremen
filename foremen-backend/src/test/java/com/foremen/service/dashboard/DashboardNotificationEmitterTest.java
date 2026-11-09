package com.foremen.service.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.service.offer.NotificationService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

/** Unit tests of {@link DashboardNotificationEmitter}. Validates: Requirements 17.1, 17.2. */
@SuppressWarnings("unchecked")
class DashboardNotificationEmitterTest {

    private final NotificationService service = mock(NotificationService.class);
    private final EntityManager em = mock(EntityManager.class);
    private final TypedQuery<Long> query = mock(TypedQuery.class);
    private final DashboardNotificationEmitter emitter = new DashboardNotificationEmitter(service);

    DashboardNotificationEmitterTest() {
        emitter.setEntityManager(em);
        when(em.createQuery(anyString(), eq(Long.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private static DashboardWidgetEntity widget(String nameRu) {
        DashboardWidgetEntity w = new DashboardWidgetEntity();
        w.setNameRu(nameRu);
        return w;
    }

    @Test
    void aiFailed_notifiesAdminWithReasonAndWidgetsLink() {
        emitter.emitAiFailed(7L, "error.dashboard.ai.failed");

        verify(service).create(7L, "notification.dashboard.ai.failed", "error.dashboard.ai.failed",
                "/dashboard-widgets");
    }

    @Test
    void aiFailed_serviceFailureIsSwallowed() {
        when(service.create(anyLong(), any(), any(), any())).thenThrow(new IllegalStateException("db"));

        emitter.emitAiFailed(7L, "x"); // must not throw
    }

    @Test
    void deactivated_notifiesEachDistinctUserOnceWithNameAndDashboardLink() {
        when(em.find(DashboardWidgetEntity.class, 3L)).thenReturn(widget("Сметы"));
        when(query.getResultList()).thenReturn(List.of(10L, 11L));

        emitter.onWidgetDeactivated(new DashboardWidgetDeactivatedEvent(3L));

        verify(service).create(10L, "notification.dashboard.widget.deactivated", "Сметы", "/");
        verify(service).create(11L, "notification.dashboard.widget.deactivated", "Сметы", "/");
        verify(service, times(2)).create(anyLong(), any(), any(), any());
    }

    @Test
    void deactivated_fansOutInChunksUntilExhausted() {
        when(em.find(DashboardWidgetEntity.class, 3L)).thenReturn(widget("W"));
        List<Long> full = new ArrayList<>();
        for (long i = 1; i <= DashboardNotificationEmitter.CHUNK_SIZE; i++) {
            full.add(i);
        }
        when(query.getResultList()).thenReturn(full, List.of(1000L));

        emitter.onWidgetDeactivated(new DashboardWidgetDeactivatedEvent(3L));

        verify(service, times(DashboardNotificationEmitter.CHUNK_SIZE + 1))
                .create(anyLong(), eq("notification.dashboard.widget.deactivated"), eq("W"), eq("/"));
        verify(query).setParameter("lastUserId", (long) DashboardNotificationEmitter.CHUNK_SIZE);
    }

    @Test
    void deactivated_oneRecipientFailureDoesNotStopOthers() {
        when(em.find(DashboardWidgetEntity.class, 3L)).thenReturn(widget("W"));
        when(query.getResultList()).thenReturn(List.of(1L, 2L));
        when(service.create(eq(1L), any(), any(), any())).thenThrow(new IllegalStateException("x"));

        emitter.onWidgetDeactivated(new DashboardWidgetDeactivatedEvent(3L));

        verify(service).create(2L, "notification.dashboard.widget.deactivated", "W", "/");
    }

    @Test
    void deactivated_unknownWidgetOrQueryFailureIsSwallowed() {
        when(em.find(DashboardWidgetEntity.class, 4L)).thenReturn(null);
        emitter.onWidgetDeactivated(new DashboardWidgetDeactivatedEvent(4L));

        when(em.find(DashboardWidgetEntity.class, 5L)).thenThrow(new IllegalStateException("db"));
        emitter.onWidgetDeactivated(new DashboardWidgetDeactivatedEvent(5L)); // must not throw

        emitter.onWidgetDeactivated(null);
        verify(service, never()).create(anyLong(), any(), any(), any());
    }
}
