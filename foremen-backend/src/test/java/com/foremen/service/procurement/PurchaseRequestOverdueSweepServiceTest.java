package com.foremen.service.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;

import com.foremen.dao.PurchaseRequestDao;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.UserEntity;

/** FOR-11-00 task 11.4 — unit tests for {@link PurchaseRequestOverdueSweepService} (R13.2, R13.4). */
class PurchaseRequestOverdueSweepServiceTest {

    // 2025-03-10 12:00 UTC -> 13:00 in Warsaw, today = 2025-03-10.
    private static final Instant NOW = Instant.parse("2025-03-10T12:00:00Z");

    private final PurchaseRequestDao dao = mock(PurchaseRequestDao.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    private PurchaseRequestOverdueSweepService service(Instant at) {
        return new PurchaseRequestOverdueSweepService(dao, publisher, Clock.fixed(at, ZoneOffset.UTC));
    }

    private static PurchaseRequestEntity request(long id, long projectId, Long buyerId,
                                                 LocalDate expected, PositionStatus... stages) {
        PurchaseRequestEntity r = new PurchaseRequestEntity();
        r.setId(id);
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        r.setProject(project);
        if (buyerId != null) {
            UserEntity buyer = new UserEntity();
            buyer.setId(buyerId);
            r.setAssignedBuyer(buyer);
        }
        r.setExpectedDeliveryDate(expected);
        List<PurchasePositionEntity> positions = new ArrayList<>();
        for (PositionStatus s : stages) {
            PurchasePositionEntity p = new PurchasePositionEntity();
            p.setStatus(s);
            positions.add(p);
        }
        r.setPositions(positions);
        return r;
    }

    @Test
    void stampsOverdueRequestAndPublishesEventOnce() {
        PurchaseRequestEntity r = request(1, 10, 5L, LocalDate.of(2025, 3, 9), PositionStatus.ORDERED);
        when(dao.findOverdueCandidates(LocalDate.of(2025, 3, 10))).thenReturn(List.of(r));

        service(NOW).sweep();

        assertThat(r.getOverdueNotifiedAt()).isEqualTo(NOW);
        verify(dao).save(r);
        verify(publisher).publishEvent(new PurchaseRequestOverdueEvent(1L, 10L, 5L));
    }

    @Test
    void unassignedRequestPublishesNullBuyerId() {
        PurchaseRequestEntity r = request(2, 10, null, LocalDate.of(2025, 3, 1), PositionStatus.PLANNED);
        when(dao.findOverdueCandidates(any())).thenReturn(List.of(r));

        service(NOW).sweep();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new PurchaseRequestOverdueEvent(2L, 10L, null));
    }

    @Test
    void skipsCompletedAndCancelledRequests() {
        PurchaseRequestEntity completed = request(3, 10, 5L, LocalDate.of(2025, 3, 1),
                PositionStatus.RECEIVED, PositionStatus.RECEIVED);
        PurchaseRequestEntity cancelled = request(4, 10, 5L, LocalDate.of(2025, 3, 1),
                PositionStatus.CANCELLED);
        when(dao.findOverdueCandidates(any())).thenReturn(List.of(completed, cancelled));

        service(NOW).sweep();

        assertThat(completed.getOverdueNotifiedAt()).isNull();
        assertThat(cancelled.getOverdueNotifiedAt()).isNull();
        verify(dao, never()).save(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void skipsAlreadyStampedRequest() {
        PurchaseRequestEntity r = request(5, 10, 5L, LocalDate.of(2025, 3, 1), PositionStatus.ORDERED);
        Instant earlier = Instant.parse("2025-03-05T00:00:00Z");
        r.setOverdueNotifiedAt(earlier);
        when(dao.findOverdueCandidates(any())).thenReturn(List.of(r));

        service(NOW).sweep();

        assertThat(r.getOverdueNotifiedAt()).isEqualTo(earlier);
        verify(dao, never()).save(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void usesWarsawDateAtBoundary() {
        // 2025-03-09 23:30 UTC = 2025-03-10 00:30 Warsaw (CET) -> today is already the 10th.
        Instant boundary = Instant.parse("2025-03-09T23:30:00Z");
        when(dao.findOverdueCandidates(LocalDate.of(2025, 3, 10))).thenReturn(List.of());

        service(boundary).sweep();

        verify(dao).findOverdueCandidates(LocalDate.of(2025, 3, 10));
    }

    @Test
    void requestExpectingTodayInWarsawIsNotOverdue() {
        // Candidate returned by the DAO with expected date == today (Warsaw) must not be stamped.
        Instant boundary = Instant.parse("2025-03-09T23:30:00Z");
        PurchaseRequestEntity r = request(6, 10, 5L, LocalDate.of(2025, 3, 10), PositionStatus.ORDERED);
        when(dao.findOverdueCandidates(any())).thenReturn(List.of(r));

        service(boundary).sweep();

        assertThat(r.getOverdueNotifiedAt()).isNull();
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void sweepIsScheduledWithDisabledByDefaultCron() throws Exception {
        Method m = PurchaseRequestOverdueSweepService.class.getMethod("sweep");
        Scheduled scheduled = m.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${foremen.procurement.request-overdue-sweep.cron:-}");
    }
}
