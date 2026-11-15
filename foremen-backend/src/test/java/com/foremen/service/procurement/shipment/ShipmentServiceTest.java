package com.foremen.service.procurement.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.controller.model.ShipmentDto;
import com.foremen.controller.model.ShipmentRequest;
import com.foremen.dao.ExternalOrderDao;
import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ShipmentDao;
import com.foremen.dao.ShipmentEventDao;
import com.foremen.dao.ShipmentLineDao;
import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.EtaSource;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import com.foremen.dao.model.ShipmentSource;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.procurement.PlannedDeliveryDateSynchronizer;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;
import com.foremen.service.procurement.schedule.MaterialScheduleRiskService;
import com.foremen.service.procurement.shipment.tracking.CarrierTrackingAdapter;
import com.foremen.service.procurement.shipment.tracking.CarrierTrackingRegistry;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/** FOR-11-04 task 3.8: create / update / cancel / revert / masking / scoping with mocked collaborators. */
class ShipmentServiceTest {

    private static final Long PROJECT_ID = 3L;

    private ShipmentDao shipmentDao;
    private ShipmentLineDao lineDao;
    private ShipmentEventDao eventDao;
    private ProjectDao projectDao;
    private ExternalOrderDao orderDao;
    private ExternalOrderLineDao orderLineDao;
    private PositionLifecycleService lifecycle;
    private PlannedDeliveryDateSynchronizer synchronizer;
    private MaterialScheduleRiskService riskService;
    private CarrierTrackingRegistry registry;
    private ProcurementProjectAccess access;
    private EntityManager em;
    private ApplicationEventPublisher events;
    private ShipmentService service;
    private ProjectEntity project;

    @BeforeEach
    void setUp() {
        shipmentDao = mock(ShipmentDao.class);
        lineDao = mock(ShipmentLineDao.class);
        eventDao = mock(ShipmentEventDao.class);
        projectDao = mock(ProjectDao.class);
        orderDao = mock(ExternalOrderDao.class);
        orderLineDao = mock(ExternalOrderLineDao.class);
        lifecycle = mock(PositionLifecycleService.class);
        synchronizer = mock(PlannedDeliveryDateSynchronizer.class);
        riskService = mock(MaterialScheduleRiskService.class);
        registry = mock(CarrierTrackingRegistry.class);
        access = mock(ProcurementProjectAccess.class);
        em = mock(EntityManager.class);
        events = mock(ApplicationEventPublisher.class);
        Clock clock = Clock.fixed(Instant.parse("2030-01-05T10:00:00Z"), ZoneOffset.UTC);
        service = new ShipmentService(shipmentDao, lineDao, eventDao, projectDao, orderDao, orderLineDao,
                lifecycle, synchronizer, riskService, registry, access, mock(AuditLogDao.class), em, events, clock);

        project = new ProjectEntity();
        project.setId(PROJECT_ID);
        project.setName("P");
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(registry.allCodes()).thenReturn(Set.of("DPD"));
        when(registry.find(any())).thenReturn(Optional.empty());
        when(registry.findAny(any())).thenReturn(Optional.empty());
        when(shipmentDao.save(any(ShipmentEntity.class))).thenAnswer(inv -> {
            ShipmentEntity s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(100L);
            }
            return s;
        });
        authenticate("7", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String name, String... roles) {
        List<SimpleGrantedAuthority> auth = new ArrayList<>();
        for (String r : roles) {
            auth.add(new SimpleGrantedAuthority(r));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(name, "x", auth));
    }

    private PurchasePositionEntity position(long id, PositionStatus status, String ordered) {
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setId(id);
        p.setProject(project);
        p.setSourcingPath(SourcingPath.AGREED);
        p.setStatus(status);
        p.setQuantity(new BigDecimal("20"));
        p.setOrderedQuantity(new BigDecimal(ordered));
        FinishingMaterialEntity m = new FinishingMaterialEntity();
        m.setModel("Tile");
        p.setPlannedMaterial(m);
        MeasurementUnitEntity u = new MeasurementUnitEntity();
        u.setNamePL("szt");
        u.setNameRU("шт");
        p.setUnit(u);
        when(em.find(PurchasePositionEntity.class, id, LockModeType.PESSIMISTIC_WRITE)).thenReturn(p);
        return p;
    }

    private static ShipmentRequest request(Long orderId, ShipmentRequest.Line... lines) {
        return new ShipmentRequest(PROJECT_ID, orderId, DeliveryMethod.PHONE_CONTACT, null, null, null, null,
                null, null, null, "Jan", "+48 600 100 200", "internal", null, List.of(lines));
    }

    private static ShipmentRequest.Line line(long positionId, String qty) {
        return new ShipmentRequest.Line(positionId, new BigDecimal(qty));
    }

    private ShipmentEntity shipment(ShipmentStatus status) {
        ShipmentEntity s = new ShipmentEntity();
        s.setId(50L);
        s.setProject(project);
        s.setDeliveryMethod(DeliveryMethod.PHONE_CONTACT);
        s.setDriverPhone("+48 600 100 200");
        s.setNote("internal");
        s.setStatus(status);
        s.setStatusSource(ShipmentSource.MANUAL);
        s.setEtaSource(EtaSource.TRACKING);
        when(shipmentDao.findById(50L)).thenReturn(Optional.of(s));
        return s;
    }

    private ShipmentLineEntity attach(ShipmentEntity s, PurchasePositionEntity p, String qty) {
        ShipmentLineEntity l = new ShipmentLineEntity();
        l.setShipment(s);
        l.setPosition(p);
        l.setQuantity(new BigDecimal(qty));
        when(lineDao.findByShipmentId(s.getId())).thenReturn(List.of(l));
        return l;
    }

    private static ForemenApiException failure(Runnable r) {
        try {
            r.run();
        } catch (ForemenApiException e) {
            return e;
        }
        throw new AssertionError("expected ForemenApiException");
    }

    // --- create ---

    @Test
    void createMovesSupplierPaidPositionToInDeliveryAndPublishesCreated() {
        PurchasePositionEntity paid = position(11L, PositionStatus.SUPPLIER_PAID, "10");
        PurchasePositionEntity inDelivery = position(12L, PositionStatus.IN_DELIVERY, "10");

        ShipmentDto dto = service.create(request(null, line(12L, "3"), line(11L, "4.5")));

        assertThat(dto.id()).isEqualTo(100L);
        assertThat(dto.status()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(dto.lines()).isEmpty(); // the line mock returns nothing: lines are saved through lineDao
        verify(lifecycle).transition(11L, PositionStatus.IN_DELIVERY, Actor.SYSTEM);
        verify(lifecycle, never()).transition(eq(12L), any(), any());
        verify(synchronizer).onShipmentChanged(11L);
        verify(synchronizer).onShipmentChanged(12L);
        ArgumentCaptor<ShipmentCreatedEvent> created = ArgumentCaptor.forClass(ShipmentCreatedEvent.class);
        verify(events).publishEvent(created.capture());
        assertThat(created.getValue().positionIds()).containsExactly(11L, 12L);
        assertThat(created.getValue().actorUserId()).isEqualTo(7L);
        verify(events, never()).publishEvent(any(ShipmentRefreshRequestedEvent.class));
        assertThat(paid.getStatus()).isEqualTo(PositionStatus.SUPPLIER_PAID); // the mocked lifecycle does not write
        assertThat(inDelivery.getStatus()).isEqualTo(PositionStatus.IN_DELIVERY);
    }

    @Test
    void createManualEtaIsManualSourced() {
        position(11L, PositionStatus.SUPPLIER_PAID, "10");
        ShipmentRequest base = request(null, line(11L, "1"));
        ShipmentRequest withEta = new ShipmentRequest(base.projectId(), null, base.deliveryMethod(), null, null,
                null, null, LocalDate.of(2030, 1, 9), null, null, "Jan", base.driverPhone(), null, null,
                base.lines());

        ShipmentDto dto = service.create(withEta);

        assertThat(dto.etaDate()).isEqualTo(LocalDate.of(2030, 1, 9));
        assertThat(dto.etaSource()).isEqualTo(EtaSource.MANUAL);
    }

    @Test
    void createRejectsQuantityAboveCap() {
        position(11L, PositionStatus.SUPPLIER_PAID, "10");
        when(lineDao.sumActiveQuantityByPositionExcluding(anyCollection(), eq(-1L)))
                .thenReturn(List.<Object[]>of(new Object[] {11L, new BigDecimal("8")}));

        ForemenApiException ex = failure(() -> service.create(request(null, line(11L, "2.001"))));

        assertThat(ex.getMessage()).contains("error.shipment.line.exceedsCap");
        verify(shipmentDao, never()).save(any());
        verify(lifecycle, never()).transition(anyLong(), any(), any());
    }

    @Test
    void createAcceptsQuantityExactlyAtCap() {
        position(11L, PositionStatus.SUPPLIER_PAID, "10");
        when(lineDao.sumActiveQuantityByPositionExcluding(anyCollection(), eq(-1L)))
                .thenReturn(List.<Object[]>of(new Object[] {11L, new BigDecimal("8")}));

        assertThat(service.create(request(null, line(11L, "2"))).id()).isEqualTo(100L);
    }

    @Test
    void createRejectsStageNotShippable() {
        position(11L, PositionStatus.WITH_BUYER, "10");

        ForemenApiException ex = failure(() -> service.create(request(null, line(11L, "1"))));

        assertThat(ex.getMessage()).contains("error.shipment.position.notShippable");
    }

    @Test
    void createRejectsDuplicateAndEmptyLines() {
        position(11L, PositionStatus.SUPPLIER_PAID, "10");

        assertThat(failure(() -> service.create(request(null, line(11L, "1"), line(11L, "2")))).getMessage())
                .contains("error.shipment.line.duplicate");
        assertThat(failure(() -> service.create(request(null))).getMessage())
                .contains("error.shipment.line.empty");
        assertThat(failure(() -> service.create(request(null, line(11L, "0")))).getMessage())
                .contains("error.shipment.line.empty");
    }

    @Test
    void createRejectsPositionOfAnotherProject() {
        PurchasePositionEntity p = position(11L, PositionStatus.SUPPLIER_PAID, "10");
        ProjectEntity other = new ProjectEntity();
        other.setId(99L);
        p.setProject(other);

        assertThat(failure(() -> service.create(request(null, line(11L, "1")))).getMessage())
                .contains("error.shipment.project.mismatch");
    }

    @Test
    void createRejectsQuantityAboveOrderLine() {
        position(11L, PositionStatus.SUPPLIER_PAID, "10");
        ExternalOrderEntity order = new ExternalOrderEntity();
        order.setId(5L);
        order.setProject(project);
        when(orderDao.findById(5L)).thenReturn(Optional.of(order));
        ExternalOrderLineEntity ol = new ExternalOrderLineEntity();
        ol.setPosition(position(11L, PositionStatus.SUPPLIER_PAID, "10"));
        ol.setQuantity(new BigDecimal("5"));
        when(orderLineDao.findByExternalOrderId(5L)).thenReturn(List.of(ol));
        when(lineDao.sumActiveQuantityByOrderExcluding(eq(5L), anyCollection(), eq(-1L)))
                .thenReturn(List.<Object[]>of(new Object[] {11L, new BigDecimal("4")}));

        ForemenApiException ex = failure(() -> service.create(request(5L, line(11L, "2"))));

        assertThat(ex.getMessage()).contains("error.shipment.line.exceedsOrderLine");
    }

    @Test
    void createTrackableCarrierRequestsFirstRefresh() {
        position(11L, PositionStatus.SUPPLIER_PAID, "10");
        CarrierTrackingAdapter adapter = mock(CarrierTrackingAdapter.class);
        when(registry.find("DPD")).thenReturn(Optional.of(adapter));
        ShipmentRequest r = new ShipmentRequest(PROJECT_ID, null, DeliveryMethod.CARRIER, "dpd", "ab 123 456",
                null, null, null, null, null, null, null, null, null, List.of(line(11L, "1")));

        ShipmentDto dto = service.create(r);

        assertThat(dto.trackingNumber()).isEqualTo("AB123456");
        assertThat(dto.refreshAvailable()).isTrue();
        verify(events).publishEvent(new ShipmentRefreshRequestedEvent(100L));
    }

    @Test
    void createWithoutProjectAccessIs404() {
        SecurityContextHolder.clearContext();
        authenticate("8", "ROLE_MANAGER");
        when(access.allowedProjectIds(8L)).thenReturn(Set.of(77L));

        ForemenApiException ex = failure(() -> service.create(request(null, line(11L, "1"))));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(shipmentDao, never()).save(any());
    }

    // --- cancel ---

    @Test
    void cancelLastOpenShipmentReversesPositionToSupplierPaid() {
        ShipmentEntity s = shipment(ShipmentStatus.CREATED);
        PurchasePositionEntity p = position(11L, PositionStatus.IN_DELIVERY, "10");
        p.setAcceptedQuantity(BigDecimal.ZERO);
        attach(s, p, "4");
        when(lineDao.findActiveByPositionIds(anyCollection())).thenReturn(List.of());

        ShipmentDto dto = service.cancel(50L);

        assertThat(dto.status()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(s.getNextCheckAt()).isNull();
        verify(lifecycle).transition(11L, PositionStatus.SUPPLIER_PAID, Actor.SYSTEM);
        verify(synchronizer).onShipmentChanged(11L);
    }

    @Test
    void cancelKeepsStageWhenAnotherOpenShipmentExists() {
        ShipmentEntity s = shipment(ShipmentStatus.CREATED);
        PurchasePositionEntity p = position(11L, PositionStatus.IN_DELIVERY, "10");
        p.setAcceptedQuantity(BigDecimal.ZERO);
        attach(s, p, "4");
        ShipmentEntity other = new ShipmentEntity();
        other.setId(51L);
        ShipmentLineEntity otherLine = new ShipmentLineEntity();
        otherLine.setShipment(other);
        otherLine.setPosition(p);
        when(lineDao.findActiveByPositionIds(anyCollection())).thenReturn(List.of(otherLine));

        service.cancel(50L);

        verify(lifecycle, never()).transition(anyLong(), any(), any());
        verify(synchronizer).onShipmentChanged(11L);
    }

    @Test
    void cancelDoesNotMoveBackReceivedOrClientOwnPositions() {
        ShipmentEntity s = shipment(ShipmentStatus.IN_TRANSIT);
        PurchasePositionEntity partly = position(11L, PositionStatus.IN_DELIVERY, "10");
        partly.setAcceptedQuantity(new BigDecimal("1"));
        attach(s, partly, "4");

        service.cancel(50L);
        verify(lifecycle, never()).transition(anyLong(), any(), any());

        PurchasePositionEntity own = position(12L, PositionStatus.IN_DELIVERY, "10");
        own.setSourcingPath(SourcingPath.CLIENT_OWN);
        own.setAcceptedQuantity(BigDecimal.ZERO);
        attach(shipment(ShipmentStatus.IN_TRANSIT), own, "4");

        service.cancel(50L);
        verify(lifecycle, never()).transition(anyLong(), any(), any());
    }

    @Test
    void cancelTerminalShipmentIsConflict() {
        shipment(ShipmentStatus.DELIVERED);

        ForemenApiException ex = failure(() -> service.cancel(50L));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getMessage()).contains("error.shipment.terminal");
    }

    // --- update / ETA ---

    @Test
    void updateManualEtaSetsManualSourceAndPublishesDelayWhenPlannedDateMovesLater() {
        ShipmentEntity s = shipment(ShipmentStatus.CREATED);
        PurchasePositionEntity p = position(11L, PositionStatus.IN_DELIVERY, "10");
        p.setPlannedDeliveryDate(LocalDate.of(2030, 1, 10));
        attach(s, p, "4");
        doAnswer(inv -> {
            p.setPlannedDeliveryDate(LocalDate.of(2030, 1, 14));
            return null;
        }).when(synchronizer).onShipmentChanged(11L);
        ShipmentRequest r = new ShipmentRequest(null, null, null, null, null, null, null,
                LocalDate.of(2030, 1, 14), null, null, "Jan", "+48 600 100 200", "internal", null, null);

        ShipmentDto dto = service.update(50L, r);

        assertThat(dto.etaSource()).isEqualTo(EtaSource.MANUAL);
        assertThat(dto.etaDate()).isEqualTo(LocalDate.of(2030, 1, 14));
        ArgumentCaptor<ShipmentEtaDelayedEvent> ev = ArgumentCaptor.forClass(ShipmentEtaDelayedEvent.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue().oldEta()).isEqualTo(LocalDate.of(2030, 1, 10));
        assertThat(ev.getValue().newEta()).isEqualTo(LocalDate.of(2030, 1, 14));
    }

    @Test
    void updateSameDayEtaMovePublishesNoDelay() {
        ShipmentEntity s = shipment(ShipmentStatus.CREATED);
        PurchasePositionEntity p = position(11L, PositionStatus.IN_DELIVERY, "10");
        p.setPlannedDeliveryDate(LocalDate.of(2030, 1, 10));
        attach(s, p, "4");
        ShipmentRequest r = new ShipmentRequest(null, null, null, null, null, null, null,
                LocalDate.of(2030, 1, 10), null, null, null, "+48 600 100 200", null, null, null);

        service.update(50L, r);

        verify(events, never()).publishEvent(any(ShipmentEtaDelayedEvent.class));
    }

    @Test
    void updateManualDeliveredStampsAndStopsScheduling() {
        ShipmentEntity s = shipment(ShipmentStatus.IN_TRANSIT);
        s.setNextCheckAt(java.time.OffsetDateTime.now());
        attach(s, position(11L, PositionStatus.IN_DELIVERY, "10"), "4");
        ShipmentRequest r = new ShipmentRequest(null, null, null, null, null, null, null, null, null, null,
                null, "+48 600 100 200", null, ShipmentStatus.DELIVERED, null);

        ShipmentDto dto = service.update(50L, r);

        assertThat(dto.status()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(dto.statusSource()).isEqualTo(ShipmentSource.MANUAL);
        assertThat(dto.deliveredAt()).isNotNull();
        assertThat(s.getNextCheckAt()).isNull();
        verify(lifecycle, never()).transition(anyLong(), any(), any());
    }

    @Test
    void updateRejectsManualCancelledStatus() {
        ShipmentEntity s = shipment(ShipmentStatus.CREATED);
        attach(s, position(11L, PositionStatus.IN_DELIVERY, "10"), "4");
        ShipmentRequest r = new ShipmentRequest(null, null, null, null, null, null, null, null, null, null,
                null, "+48 600 100 200", null, ShipmentStatus.CANCELLED, null);

        assertThat(failure(() -> service.update(50L, r)).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateTerminalShipmentChangesOnlyTheNote() {
        ShipmentEntity s = shipment(ShipmentStatus.DELIVERED);
        attach(s, position(11L, PositionStatus.IN_DELIVERY, "10"), "4");
        ShipmentRequest noteOnly = new ShipmentRequest(null, null, null, null, null, null, null, null, null, null,
                "Changed", "999", "new note", null, null);

        ShipmentDto dto = service.update(50L, noteOnly);

        assertThat(dto.note()).isEqualTo("new note");
        assertThat(dto.driverPhone()).isEqualTo("+48 600 100 200");
        ShipmentRequest etaChange = new ShipmentRequest(null, null, null, null, null, null, null,
                LocalDate.of(2030, 2, 1), null, null, null, null, null, null, null);
        assertThat(failure(() -> service.update(50L, etaChange)).getMessage()).contains("error.shipment.terminal");
    }

    @Test
    void updateLinesOnlyWhileCreated() {
        ShipmentEntity s = shipment(ShipmentStatus.IN_TRANSIT);
        attach(s, position(11L, PositionStatus.IN_DELIVERY, "10"), "4");
        position(12L, PositionStatus.SUPPLIER_PAID, "10");
        ShipmentRequest r = new ShipmentRequest(null, null, null, null, null, null, null, null, null, null,
                null, "+48 600 100 200", null, null, List.of(line(12L, "1")));

        assertThat(failure(() -> service.update(50L, r)).getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    // --- revert ETA ---

    @Test
    void revertEtaClearsManualEtaAndRequestsRefresh() {
        ShipmentEntity s = shipment(ShipmentStatus.IN_TRANSIT);
        s.setDeliveryMethod(DeliveryMethod.CARRIER);
        s.setCarrierCode("DPD");
        s.setTrackingNumber("AB123456");
        s.setEtaDate(LocalDate.of(2030, 1, 14));
        s.setEtaSource(EtaSource.MANUAL);
        when(registry.find("DPD")).thenReturn(Optional.of(mock(CarrierTrackingAdapter.class)));
        attach(s, position(11L, PositionStatus.IN_DELIVERY, "10"), "4");

        ShipmentDto dto = service.revertEta(50L);

        assertThat(dto.etaSource()).isEqualTo(EtaSource.TRACKING);
        assertThat(dto.etaDate()).isNull();
        assertThat(s.getNextCheckAt()).isNotNull();
        verify(events).publishEvent(new ShipmentRefreshRequestedEvent(50L));
    }

    @Test
    void revertEtaNotAvailableForManualOnlyShipment() {
        ShipmentEntity s = shipment(ShipmentStatus.IN_TRANSIT);
        s.setEtaSource(EtaSource.MANUAL);

        assertThat(failure(() -> service.revertEta(50L)).getMessage())
                .contains("error.shipment.eta.revertNotAvailable");
    }

    // --- reads, masking, scoping ---

    @Test
    void foremanDoesNotSeeNoteButSeesDriverContact() {
        ShipmentEntity s = shipment(ShipmentStatus.IN_TRANSIT);
        attach(s, position(11L, PositionStatus.IN_DELIVERY, "10"), "4");
        SecurityContextHolder.clearContext();
        authenticate("9", "ROLE_FOREMAN");
        when(access.allowedProjectIds(9L)).thenReturn(Set.of(PROJECT_ID));

        ShipmentDto dto = service.get(50L);

        assertThat(dto.note()).isNull();
        assertThat(dto.driverPhone()).isEqualTo("+48 600 100 200");
    }

    @Test
    void managerSeesNote() {
        ShipmentEntity s = shipment(ShipmentStatus.IN_TRANSIT);
        attach(s, position(11L, PositionStatus.IN_DELIVERY, "10"), "4");
        SecurityContextHolder.clearContext();
        authenticate("9", "ROLE_MANAGER");
        when(access.allowedProjectIds(9L)).thenReturn(Set.of(PROJECT_ID));

        assertThat(service.get(50L).note()).isEqualTo("internal");
    }

    @Test
    void getOutsideProjectScopeIs404AndUnknownIs404() {
        shipment(ShipmentStatus.CREATED);
        SecurityContextHolder.clearContext();
        authenticate("9", "ROLE_FOREMAN");
        when(access.allowedProjectIds(9L)).thenReturn(Set.of(77L));

        assertThat(failure(() -> service.get(50L)).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(failure(() -> service.get(404L)).getMessage()).contains("error.shipment.notFound");
    }

    @Test
    void shipmentDtoHasNoMoneyComponent() {
        for (var c : ShipmentDto.class.getRecordComponents()) {
            assertThat(c.getName().toLowerCase()).doesNotMatch(".*(price|cost|amount|margin|vat|net|gross).*");
        }
        for (var c : ShipmentDto.Line.class.getRecordComponents()) {
            assertThat(c.getName().toLowerCase()).doesNotMatch(".*(price|cost|amount|margin|vat|net|gross).*");
        }
    }

    @Test
    void auditSnapshotCarriesNoPhoneNoteOrMoney() {
        ShipmentEntity s = shipment(ShipmentStatus.CREATED);
        String json = service.snapshot(s, List.of(line(11L, "2")));

        assertThat(json).contains("\"status\":\"CREATED\"").contains("\"positionId\":11");
        assertThat(json).doesNotContain("600").doesNotContain("internal").doesNotContainIgnoringCase("price");
    }

    @Test
    void cancelDoesNotReverseWhenThePositionHasADeliveredShipment() {
        // R6.3 clarification: a delivered shipment means the position awaits receipt, so it stays IN_DELIVERY.
        ShipmentEntity s = shipment(ShipmentStatus.CREATED);
        PurchasePositionEntity p = position(11L, PositionStatus.IN_DELIVERY, "10");
        p.setAcceptedQuantity(BigDecimal.ZERO);
        attach(s, p, "4");
        when(lineDao.findActiveByPositionIds(anyCollection())).thenReturn(List.of());
        when(lineDao.existsDeliveredByPositionId(11L)).thenReturn(true);
        service.cancel(50L);
        verify(lifecycle, never()).transition(anyLong(), any(), any());
        verify(synchronizer).onShipmentChanged(11L);
    }
}
