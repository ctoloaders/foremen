package com.foremen.service.procurement.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.controller.model.GoodsReceiptBatchResult;
import com.foremen.controller.model.GoodsReceiptDto;
import com.foremen.controller.model.GoodsReceiptEventRequest;
import com.foremen.controller.model.GoodsReceiptRequest;
import com.foremen.controller.model.GoodsReceiptVoidRequest;
import com.foremen.controller.model.GoodsReceiptVoidResult;
import com.foremen.dao.GoodsReceiptDao;
import com.foremen.dao.GoodsReceiptMediaDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.ShipmentDao;
import com.foremen.dao.ShipmentLineDao;
import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.GoodsReceiptEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ReceiptKind;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;
import com.foremen.service.procurement.receipt.GoodsReceiptRules.Ledger;
import com.foremen.service.procurement.shipment.ShipmentService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * FOR-11-05 task 3.3 - service tests for {@link GoodsReceiptService} over in-memory DAOs.
 *
 * <p>The DAOs are Mockito stubs that answer from a small in-memory store ({@link Fixture}); the ledger service,
 * the rules and the receipt service are the real classes. {@link ShipmentService} and
 * {@link PositionLifecycleService} are stubbed with in-memory effects. There is no database, so a rolled-back
 * transaction is checked through the side effects that precede commit (see Property 10).
 *
 * <p><b>Property 5:</b> void equals removal, {@code acceptedQuantity} in step with the ledger.
 * <b>Property 9:</b> idempotent retry. <b>Property 10:</b> batch atomicity.
 *
 * <p>Validates: Requirements 3.3, 4.1, 4.8, 5.1, 5.3, 5.4, 5.5, 6.3, 8.2, 8.4, 8.6
 */
class GoodsReceiptServiceTest {

    private static final long PROJECT = 10L;
    private static final long OTHER_PROJECT = 20L;
    private static final long USER = 5L;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------------------------------
    // In-memory world
    // ---------------------------------------------------------------------------------------------

    static final class Fixture {
        final AtomicLong ids = new AtomicLong(1000);
        final Map<Long, PurchasePositionEntity> positions = new LinkedHashMap<>();
        final Map<Long, ShipmentEntity> shipments = new LinkedHashMap<>();
        final List<ShipmentLineEntity> lines = new ArrayList<>();
        final List<GoodsReceiptEntity> receipts = new ArrayList<>();
        final List<PositionStatus> transitions = new ArrayList<>();
        final List<Long> closed = new ArrayList<>();
        final List<Long> cancelled = new ArrayList<>();
        final List<Object[]> redeliveries = new ArrayList<>();
        final List<Object> published = new ArrayList<>();
        final Set<Long> allowedProjects = new java.util.HashSet<>();

        final GoodsReceiptDao receiptDao = mock(GoodsReceiptDao.class);
        final PurchasePositionDao positionDao = mock(PurchasePositionDao.class);
        final ShipmentDao shipmentDao = mock(ShipmentDao.class);
        final ShipmentLineDao lineDao = mock(ShipmentLineDao.class);
        final ShipmentService shipmentService = mock(ShipmentService.class);
        final PositionLifecycleService lifecycle = mock(PositionLifecycleService.class);
        final ProcurementProjectAccess access = mock(ProcurementProjectAccess.class);
        final EntityManager em = mock(EntityManager.class);
        final GoodsReceiptService service;
        final GoodsReceiptLedgerService ledgerService;

        Fixture() {
            this(true);
        }

        Fixture(boolean admin) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    String.valueOf(USER), "n/a",
                    List.of(new SimpleGrantedAuthority(admin ? "ROLE_ADMIN" : "ROLE_FOREMAN"))));
            wireDaos();
            ledgerService = new GoodsReceiptLedgerService(positionDao, receiptDao, lineDao);
            ApplicationEventPublisher publisher = published::add;
            Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
            service = new GoodsReceiptService(receiptDao, mock(GoodsReceiptMediaDao.class), ledgerService,
                    positionDao, shipmentDao, lineDao, shipmentService, lifecycle, access,
                    mock(AuditLogDao.class), new GoodsReceiptProperties(null, null), em, publisher, clock);
            when(access.allowedProjectIds(USER)).thenReturn(allowedProjects);
        }

        PurchasePositionEntity position(long id, long projectId, int quantity, PositionStatus status) {
            PurchasePositionEntity p = new PurchasePositionEntity();
            p.setId(id);
            ProjectEntity project = new ProjectEntity();
            project.setId(projectId);
            p.setProject(project);
            p.setSourcingPath(SourcingPath.PACKAGE);
            p.setQuantity(BigDecimal.valueOf(quantity));
            p.setOrderedQuantity(BigDecimal.valueOf(quantity));
            p.setAcceptedQuantity(BigDecimal.ZERO);
            p.setStatus(status);
            positions.put(id, p);
            return p;
        }

        ShipmentEntity shipment(long id, long projectId, LocalDate eta, long positionId, int quantity) {
            ShipmentEntity s = new ShipmentEntity();
            s.setId(id);
            ProjectEntity project = new ProjectEntity();
            project.setId(projectId);
            s.setProject(project);
            s.setDeliveryMethod(DeliveryMethod.SHOP_DELIVERY);
            s.setStatus(ShipmentStatus.IN_TRANSIT);
            s.setEtaDate(eta);
            shipments.put(id, s);
            line(s, positionId, quantity);
            return s;
        }

        void line(ShipmentEntity s, long positionId, int quantity) {
            ShipmentLineEntity l = new ShipmentLineEntity();
            l.setId(ids.incrementAndGet());
            l.setShipment(s);
            l.setPosition(positions.get(positionId));
            l.setQuantity(BigDecimal.valueOf(quantity));
            lines.add(l);
        }

        /** Inserts a stored event directly (used to rebuild the "remaining events only" world). */
        void rawRow(long positionId, Long shipmentId, ReceiptKind kind, int quantity, boolean fromAccepted) {
            GoodsReceiptEntity r = new GoodsReceiptEntity();
            r.setId(ids.incrementAndGet());
            r.setProject(positions.get(positionId).getProject());
            r.setPosition(positions.get(positionId));
            r.setShipment(shipmentId == null ? null : shipments.get(shipmentId));
            r.setKind(kind);
            r.setQuantity(BigDecimal.valueOf(quantity));
            r.setFromAccepted(fromAccepted);
            receipts.add(r);
        }

        /**
         * Models the database transaction: when the body throws, stored rows, shipments, lines, void marks and
         * the position columns return to their state before the call (rollback), then the exception propagates.
         */
        <T> T tx(java.util.function.Supplier<T> body) {
            int receiptCount = receipts.size();
            int lineCount = lines.size();
            Set<Long> shipmentKeys = new java.util.HashSet<>(shipments.keySet());
            Map<GoodsReceiptEntity, Object[]> voids = new LinkedHashMap<>();
            receipts.forEach(r -> voids.put(r, new Object[] {r.getVoidedAt(), r.getVoidedBy(), r.getVoidReason()}));
            Map<PurchasePositionEntity, Object[]> cols = new LinkedHashMap<>();
            positions.values().forEach(p -> cols.put(p, new Object[] {p.getStatus(), p.getAcceptedQuantity()}));
            Map<ShipmentEntity, ShipmentStatus> statuses = new LinkedHashMap<>();
            shipments.values().forEach(s -> statuses.put(s, s.getStatus()));
            try {
                return body.get();
            } catch (RuntimeException e) {
                while (receipts.size() > receiptCount) {
                    receipts.remove(receipts.size() - 1);
                }
                while (lines.size() > lineCount) {
                    lines.remove(lines.size() - 1);
                }
                shipments.keySet().retainAll(shipmentKeys);
                voids.forEach((r, v) -> {
                    r.setVoidedAt((java.time.OffsetDateTime) v[0]);
                    r.setVoidedBy((UserEntity) v[1]);
                    r.setVoidReason((String) v[2]);
                });
                cols.forEach((p, v) -> {
                    p.setStatus((PositionStatus) v[0]);
                    p.setAcceptedQuantity((BigDecimal) v[1]);
                });
                statuses.forEach(ShipmentEntity::setStatus);
                throw e;
            }
        }

        List<GoodsReceiptEntity> active() {
            return receipts.stream().filter(r -> r.getVoidedAt() == null).toList();
        }

        BigDecimal acceptedNetFromStore(long positionId) {
            BigDecimal sum = BigDecimal.ZERO;
            for (GoodsReceiptEntity r : active()) {
                if (!r.getPosition().getId().equals(positionId)) {
                    continue;
                }
                if (r.getKind() == ReceiptKind.ACCEPTED) {
                    sum = sum.add(r.getQuantity());
                } else if (r.getKind() == ReceiptKind.RETURNED && r.isFromAccepted()) {
                    sum = sum.subtract(r.getQuantity());
                }
            }
            return sum;
        }

        private boolean live(ShipmentLineEntity l) {
            return l.getShipment().getStatus() != ShipmentStatus.CANCELLED;
        }

        @SuppressWarnings("unchecked")
        private void wireDaos() {
            when(receiptDao.save(any(GoodsReceiptEntity.class))).thenAnswer(inv -> {
                GoodsReceiptEntity r = inv.getArgument(0);
                if (r.getId() == null) {
                    r.setId(ids.incrementAndGet());
                }
                if (!receipts.contains(r)) {
                    receipts.add(r);
                }
                return r;
            });
            when(receiptDao.findById(anyLong())).thenAnswer(inv -> receipts.stream()
                    .filter(r -> r.getId().equals(inv.getArgument(0))).findFirst());
            when(receiptDao.findByClientRequestIdOrderByPartNo(any(UUID.class))).thenAnswer(inv -> receipts.stream()
                    .filter(r -> inv.getArgument(0).equals(r.getClientRequestId()))
                    .sorted(Comparator.comparingInt(GoodsReceiptEntity::getPartNo)).toList());
            when(receiptDao.sumsByPositionAndKind(anyCollection())).thenAnswer(inv -> {
                Collection<Long> ids = inv.getArgument(0);
                Map<String, Object[]> grouped = new LinkedHashMap<>();
                for (GoodsReceiptEntity r : active()) {
                    if (!ids.contains(r.getPosition().getId())) {
                        continue;
                    }
                    String key = r.getPosition().getId() + "/" + r.getKind() + "/" + r.isFromAccepted();
                    Object[] row = grouped.computeIfAbsent(key,
                            k -> new Object[] {r.getPosition().getId(), r.getKind(), r.isFromAccepted(),
                                    BigDecimal.ZERO});
                    row[3] = ((BigDecimal) row[3]).add(r.getQuantity());
                }
                return new ArrayList<>(grouped.values());
            });
            when(receiptDao.sumsByShipmentAndPosition(anyCollection())).thenAnswer(inv -> {
                Collection<Long> ids = inv.getArgument(0);
                Map<String, Object[]> grouped = new LinkedHashMap<>();
                for (GoodsReceiptEntity r : active()) {
                    if (r.getShipment() == null || r.isFromAccepted() || !ids.contains(r.getShipment().getId())) {
                        continue;
                    }
                    String key = r.getShipment().getId() + "/" + r.getPosition().getId() + "/" + r.getKind();
                    Object[] row = grouped.computeIfAbsent(key,
                            k -> new Object[] {r.getShipment().getId(), r.getPosition().getId(), r.getKind(),
                                    BigDecimal.ZERO});
                    row[3] = ((BigDecimal) row[3]).add(r.getQuantity());
                }
                return new ArrayList<>(grouped.values());
            });
            when(receiptDao.existsActiveByPositionId(anyLong())).thenAnswer(inv -> active().stream()
                    .anyMatch(r -> r.getPosition().getId().equals(inv.getArgument(0))));
            when(receiptDao.existsActiveByShipmentId(anyLong())).thenAnswer(inv -> active().stream()
                    .anyMatch(r -> r.getShipment() != null && r.getShipment().getId().equals(inv.getArgument(0))));
            when(receiptDao.findFifoLinesByPositionId(anyLong())).thenAnswer(inv -> lines.stream()
                    .filter(l -> l.getPosition().getId().equals(inv.getArgument(0)) && live(l))
                    .sorted(Comparator
                            .comparing((ShipmentLineEntity l) -> l.getShipment().getEtaDate(),
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(l -> l.getShipment().getId()))
                    .toList());

            when(positionDao.findAllById(anyCollection())).thenAnswer(inv -> {
                Collection<Long> ids = inv.getArgument(0);
                return positions.values().stream().filter(p -> ids.contains(p.getId())).toList();
            });
            when(positionDao.findById(anyLong()))
                    .thenAnswer(inv -> Optional.ofNullable(positions.get((Long) inv.getArgument(0))));
            when(positionDao.save(any(PurchasePositionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            when(lineDao.sumActiveQuantityByPositionExcluding(anyCollection(), anyLong())).thenAnswer(inv -> {
                Collection<Long> ids = inv.getArgument(0);
                Long excluded = inv.getArgument(1);
                Map<Long, BigDecimal> sums = new LinkedHashMap<>();
                for (ShipmentLineEntity l : lines) {
                    if (live(l) && ids.contains(l.getPosition().getId())
                            && !l.getShipment().getId().equals(excluded)) {
                        sums.merge(l.getPosition().getId(), l.getQuantity(), BigDecimal::add);
                    }
                }
                List<Object[]> rows = new ArrayList<>();
                sums.forEach((k, v) -> rows.add(new Object[] {k, v}));
                return rows;
            });
            when(lineDao.findByShipmentId(anyLong())).thenAnswer(inv -> lines.stream()
                    .filter(l -> l.getShipment().getId().equals(inv.getArgument(0))).toList());
            when(shipmentDao.findById(anyLong()))
                    .thenAnswer(inv -> Optional.ofNullable(shipments.get((Long) inv.getArgument(0))));
            when(shipmentDao.findByOriginReceiptId(anyLong())).thenAnswer(inv -> shipments.values().stream()
                    .filter(s -> inv.getArgument(0).equals(s.getOriginReceiptId())).toList());

            when(em.find(eq(PurchasePositionEntity.class), any(), eq(LockModeType.PESSIMISTIC_WRITE)))
                    .thenAnswer(inv -> positions.get((Long) inv.getArgument(1)));
            when(em.getReference(eq(UserEntity.class), any())).thenAnswer(inv -> {
                UserEntity u = new UserEntity();
                u.setId((Long) inv.getArgument(1));
                return u;
            });

            when(lifecycle.transition(anyLong(), any(PositionStatus.class), any(Actor.class))).thenAnswer(inv -> {
                positions.get((Long) inv.getArgument(0)).setStatus(inv.getArgument(1));
                transitions.add(inv.getArgument(1));
                return null;
            });
            when(shipmentService.createRedelivery(anyLong(), any(BigDecimal.class), any())).thenAnswer(inv -> {
                long id = ids.incrementAndGet();
                long positionId = inv.getArgument(0);
                ShipmentEntity s = new ShipmentEntity();
                s.setId(id);
                s.setProject(positions.get(positionId).getProject());
                s.setDeliveryMethod(DeliveryMethod.SHOP_DELIVERY);
                s.setStatus(ShipmentStatus.CREATED);
                s.setOriginReceiptId(inv.getArgument(2));
                shipments.put(id, s);
                ShipmentLineEntity l = new ShipmentLineEntity();
                l.setId(ids.incrementAndGet());
                l.setShipment(s);
                l.setPosition(positions.get(positionId));
                l.setQuantity(inv.getArgument(1));
                lines.add(l);
                redeliveries.add(new Object[] {positionId, inv.getArgument(1), inv.getArgument(2), id});
                return id;
            });
            when(shipmentService.cancel(anyLong())).thenAnswer(inv -> {
                shipments.get((Long) inv.getArgument(0)).setStatus(ShipmentStatus.CANCELLED);
                cancelled.add(inv.getArgument(0));
                return null;
            });
            org.mockito.Mockito.doAnswer(inv -> {
                shipments.get((Long) inv.getArgument(0)).setStatus(ShipmentStatus.DELIVERED);
                closed.add(inv.getArgument(0));
                return null;
            }).when(shipmentService).closeByReceipt(anyLong());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static GoodsReceiptEventRequest ev(long positionId, Long shipmentId, ReceiptKind kind, int quantity,
                                               boolean fromAccepted, UUID requestId) {
        String comment = kind == ReceiptKind.ACCEPTED ? null : "reason";
        return new GoodsReceiptEventRequest(positionId, shipmentId, kind, BigDecimal.valueOf(quantity),
                fromAccepted, comment, requestId);
    }

    private static GoodsReceiptEventRequest accepted(long positionId, int quantity) {
        return ev(positionId, null, ReceiptKind.ACCEPTED, quantity, false, null);
    }

    private static GoodsReceiptRequest batch(GoodsReceiptEventRequest... events) {
        return new GoodsReceiptRequest(List.of(events));
    }

    /** Position 1 (cap 3) with one shipment 100 of 3 units, in delivery. */
    private static Fixture simple() {
        Fixture f = new Fixture();
        f.position(1L, PROJECT, 3, PositionStatus.IN_DELIVERY);
        f.shipment(100L, PROJECT, LocalDate.of(2026, 3, 5), 1L, 3);
        return f;
    }

    private static void assertApi(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ForemenApiException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(status.value());
            assertThat(e.getMessageCode()).isEqualTo(code);
        });
    }

    private static void assertSame(Ledger a, Ledger b) {
        assertThat(a.cap()).isEqualByComparingTo(b.cap());
        assertThat(a.acceptedNet()).isEqualByComparingTo(b.acceptedNet());
        assertThat(a.missing()).isEqualByComparingTo(b.missing());
        assertThat(a.returned()).isEqualByComparingTo(b.returned());
        assertThat(a.outstanding()).isEqualByComparingTo(b.outstanding());
        assertThat(a.surplus()).isEqualByComparingTo(b.surplus());
        assertThat(a.inFlight()).isEqualByComparingTo(b.inFlight());
        assertThat(a.committed()).isEqualByComparingTo(b.committed());
        assertThat(a.remainder()).isEqualByComparingTo(b.remainder());
    }

    private static void setCaller(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(USER), "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // ---------------------------------------------------------------------------------------------
    // FIFO split and closure (R4, R8.6)
    // ---------------------------------------------------------------------------------------------

    @Test
    void acceptedWithoutShipmentIsSplitFifoByEtaThenSurplusUnlinked() {
        Fixture f = new Fixture();
        f.position(1L, PROJECT, 4, PositionStatus.IN_DELIVERY);
        f.shipment(100L, PROJECT, LocalDate.of(2026, 3, 9), 1L, 2); // later ETA, lower id
        f.shipment(101L, PROJECT, LocalDate.of(2026, 3, 4), 1L, 2); // earlier ETA, served first
        UUID id = UUID.randomUUID();

        f.service.record(batch(ev(1L, null, ReceiptKind.ACCEPTED, 5, false, id)));

        List<GoodsReceiptEntity> rows = f.receipts;
        assertThat(rows).extracting(r -> r.getShipment() == null ? null : r.getShipment().getId())
                .containsExactly(101L, 100L, null);
        assertThat(rows).extracting(r -> r.getQuantity().intValue()).containsExactly(2, 2, 1);
        assertThat(rows).extracting(GoodsReceiptEntity::getPartNo).containsExactly((short) 0, (short) 1, (short) 2);
        assertThat(rows).allSatisfy(r -> assertThat(r.getClientRequestId()).isEqualTo(id));
        // surplus 1 over cap 4: one discrepancy event for the request event, not one per part
        assertThat(f.published).hasSize(1);
    }

    @Test
    void closesShipmentWhenEveryLineResolvedAndLeavesPartialOpen() {
        Fixture f = simple();

        f.service.record(batch(accepted(1L, 1)));
        assertThat(f.closed).isEmpty();
        assertThat(f.positions.get(1L).getStatus()).isEqualTo(PositionStatus.PARTIALLY_RECEIVED);
        assertThat(f.positions.get(1L).getAcceptedQuantity()).isEqualByComparingTo("1");

        f.service.record(batch(accepted(1L, 2)));
        assertThat(f.closed).containsExactly(100L);
        assertThat(f.positions.get(1L).getStatus()).isEqualTo(PositionStatus.RECEIVED);
        assertThat(f.positions.get(1L).getAcceptedQuantity()).isEqualByComparingTo("3");
    }

    // ---------------------------------------------------------------------------------------------
    // Re-delivery (R8.2, R8.4)
    // ---------------------------------------------------------------------------------------------

    @Test
    void oneRedeliveryPerPositionWithReleasedQuantityFromFinalLedger() {
        Fixture f = simple();

        GoodsReceiptBatchResult result = f.service.record(batch(
                accepted(1L, 1),
                ev(1L, null, ReceiptKind.RETURNED, 1, false, null),
                ev(1L, null, ReceiptKind.MISSING, 1, false, null)));

        assertThat(f.redeliveries).hasSize(1);
        Object[] redelivery = f.redeliveries.get(0);
        assertThat(redelivery[0]).isEqualTo(1L);
        assertThat((BigDecimal) redelivery[1]).isEqualByComparingTo("2");
        assertThat(result.redeliveryShipmentIds()).containsExactly((Long) redelivery[3]);
        // origin receipt = first released row (the door return), not the accepted row
        GoodsReceiptEntity returned = f.receipts.stream()
                .filter(r -> r.getKind() == ReceiptKind.RETURNED).findFirst().orElseThrow();
        assertThat(redelivery[2]).isEqualTo(returned.getId());
        assertThat(f.positions.get(1L).getStatus()).isEqualTo(PositionStatus.PARTIALLY_RECEIVED);
        // the original line is fully resolved (1 accepted + 1 returned + 1 missing)
        assertThat(f.closed).containsExactly(100L);
    }

    @Test
    void redeliveryIsLimitedToReleasedUnitsNotUnshippedRemainder() {
        Fixture f = new Fixture();
        f.position(1L, PROJECT, 3, PositionStatus.IN_DELIVERY);
        f.shipment(100L, PROJECT, LocalDate.of(2026, 3, 5), 1L, 2); // one unit never shipped

        f.service.record(batch(accepted(1L, 1), ev(1L, null, ReceiptKind.RETURNED, 1, false, null)));

        assertThat(f.redeliveries).hasSize(1);
        assertThat((BigDecimal) f.redeliveries.get(0)[1]).isEqualByComparingTo("1");
        assertThat(f.service.ledger(1L).ledger().outstanding()).isEqualByComparingTo("2");
    }

    @Test
    void noRedeliveryForAcceptedOnlyOrReturnFromAccepted() {
        Fixture f = simple();
        f.service.record(batch(accepted(1L, 3)));
        f.service.record(batch(ev(1L, null, ReceiptKind.RETURNED, 1, true, null)));
        assertThat(f.redeliveries).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Void (R5)
    // ---------------------------------------------------------------------------------------------

    @Test
    void voidCancelsUntouchedRedelivery() {
        Fixture f = simple();
        f.service.record(batch(ev(1L, null, ReceiptKind.MISSING, 1, false, null)));
        Long receiptId = f.receipts.get(0).getId();
        Long redeliveryId = (Long) f.redeliveries.get(0)[3];

        GoodsReceiptVoidResult result = f.service.voidReceipt(receiptId, new GoodsReceiptVoidRequest("entered by mistake"));

        assertThat(f.cancelled).containsExactly(redeliveryId);
        assertThat(result.redeliveryKept()).isFalse();
        assertThat(result.receipt().voidedAt()).isNotNull();
        assertThat(f.positions.get(1L).getStatus()).isEqualTo(PositionStatus.IN_DELIVERY);
    }

    @Test
    void voidKeepsRedeliveryTheBuyerHasAlreadyEdited() {
        Fixture f = simple();
        f.service.record(batch(ev(1L, null, ReceiptKind.MISSING, 1, false, null)));
        Long receiptId = f.receipts.get(0).getId();
        f.shipments.get((Long) f.redeliveries.get(0)[3]).setTrackingNumber("TRK-1");

        GoodsReceiptVoidResult result = f.service.voidReceipt(receiptId, new GoodsReceiptVoidRequest("mistake"));

        assertThat(f.cancelled).isEmpty();
        assertThat(result.redeliveryKept()).isTrue();
    }

    @Test
    void returnFromAcceptedOnReceivedPositionStepsBackToPartiallyReceived() {
        Fixture f = simple();
        f.service.record(batch(accepted(1L, 3)));
        assertThat(f.positions.get(1L).getStatus()).isEqualTo(PositionStatus.RECEIVED);

        f.service.record(batch(ev(1L, null, ReceiptKind.RETURNED, 1, true, null)));

        assertThat(f.positions.get(1L).getStatus()).isEqualTo(PositionStatus.PARTIALLY_RECEIVED);
        assertThat(f.positions.get(1L).getAcceptedQuantity()).isEqualByComparingTo("2");
        // R4: a door return or new acceptance on a RECEIVED position is not receivable
        f.positions.get(1L).setStatus(PositionStatus.RECEIVED);
        assertApi(() -> f.service.record(batch(ev(1L, null, ReceiptKind.RETURNED, 1, false, null))),
                HttpStatus.CONFLICT, "error.goodsReceipt.notReceivable");
        assertApi(() -> f.service.record(batch(accepted(1L, 1))),
                HttpStatus.CONFLICT, "error.goodsReceipt.notReceivable");
    }

    @Test
    void voidingTheOnlyEventOfReceivedPositionStepsThroughPartiallyReceivedToInDelivery() {
        Fixture f = simple();
        f.service.record(batch(accepted(1L, 3)));
        assertThat(f.positions.get(1L).getStatus()).isEqualTo(PositionStatus.RECEIVED);
        f.transitions.clear();

        f.service.voidReceipt(f.receipts.get(0).getId(), new GoodsReceiptVoidRequest("wrong position"));

        assertThat(f.transitions).containsExactly(PositionStatus.PARTIALLY_RECEIVED, PositionStatus.IN_DELIVERY);
        assertThat(f.positions.get(1L).getStatus()).isEqualTo(PositionStatus.IN_DELIVERY);
        assertThat(f.positions.get(1L).getAcceptedQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void voidingEventsOneByOneWalksReceivedPartialInDelivery() {
        Fixture f = simple();
        f.service.record(batch(accepted(1L, 2)));
        f.service.record(batch(accepted(1L, 1)));
        assertThat(f.positions.get(1L).getStatus()).isEqualTo(PositionStatus.RECEIVED);
        Long second = f.receipts.get(1).getId();
        Long first = f.receipts.get(0).getId();

        f.service.voidReceipt(second, new GoodsReceiptVoidRequest("x"));
        assertThat(f.positions.get(1L).getStatus()).isEqualTo(PositionStatus.PARTIALLY_RECEIVED);
        assertThat(f.positions.get(1L).getAcceptedQuantity()).isEqualByComparingTo("2");

        f.service.voidReceipt(first, new GoodsReceiptVoidRequest("x"));
        assertThat(f.positions.get(1L).getStatus()).isEqualTo(PositionStatus.IN_DELIVERY);
        assertThat(f.positions.get(1L).getAcceptedQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void voidNeverReopensShipmentAndNeverNotifies() {
        Fixture f = simple();
        f.service.record(batch(accepted(1L, 3)));
        int eventsBefore = f.published.size();
        f.closed.clear();

        f.service.voidReceipt(f.receipts.get(0).getId(), new GoodsReceiptVoidRequest("oops"));

        assertThat(f.published).hasSize(eventsBefore);
        assertThat(f.shipments.get(100L).getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
    }

    @Test
    void voidRequiresReasonAndRejectsSecondVoid() {
        Fixture f = simple();
        f.service.record(batch(accepted(1L, 1)));
        Long id = f.receipts.get(0).getId();

        assertApi(() -> f.service.voidReceipt(id, new GoodsReceiptVoidRequest("  ")),
                HttpStatus.BAD_REQUEST, "error.goodsReceipt.comment.required");
        f.service.voidReceipt(id, new GoodsReceiptVoidRequest("ok"));
        assertApi(() -> f.service.voidReceipt(id, new GoodsReceiptVoidRequest("again")),
                HttpStatus.CONFLICT, "error.goodsReceipt.alreadyVoided");
        assertThat(f.positions.get(1L).getAcceptedQuantity()).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------------------------------------
    // Scope (R1)
    // ---------------------------------------------------------------------------------------------

    @Test
    void positionOutsideCallerProjectsReadsAsNotFoundAndStoresNothing() {
        Fixture f = new Fixture(false);
        f.allowedProjects.add(PROJECT);
        f.position(2L, OTHER_PROJECT, 3, PositionStatus.IN_DELIVERY);
        f.shipment(200L, OTHER_PROJECT, LocalDate.of(2026, 3, 5), 2L, 3);

        assertApi(() -> f.service.record(batch(accepted(2L, 1))), HttpStatus.NOT_FOUND, "error.entity.not.found");
        assertApi(() -> f.service.ledger(2L), HttpStatus.NOT_FOUND, "error.entity.not.found");
        assertThat(f.receipts).isEmpty();
        assertThat(f.published).isEmpty();
    }

    @Test
    void receiptOutsideCallerProjectsCannotBeVoided() {
        Fixture f = new Fixture(true);
        f.position(2L, OTHER_PROJECT, 3, PositionStatus.IN_DELIVERY);
        f.shipment(200L, OTHER_PROJECT, LocalDate.of(2026, 3, 5), 2L, 3);
        f.service.record(batch(accepted(2L, 1)));
        Long id = f.receipts.get(0).getId();

        setCaller("MANAGER");
        f.allowedProjects.add(PROJECT);

        assertApi(() -> f.service.voidReceipt(id, new GoodsReceiptVoidRequest("x")),
                HttpStatus.NOT_FOUND, "error.entity.not.found");
        assertThat(f.receipts.get(0).getVoidedAt()).isNull();
    }

    // ---------------------------------------------------------------------------------------------
    // Publisher-side discrepancy events (R12)
    // ---------------------------------------------------------------------------------------------

    @Test
    void discrepancyEventOnlyForMissingReturnedAndSurplusAccepted() {
        Fixture f = simple();

        f.service.record(batch(accepted(1L, 1)));
        assertThat(f.published).as("clean ACCEPTED").isEmpty();

        f.service.record(batch(ev(1L, null, ReceiptKind.MISSING, 1, false, null)));
        assertThat(f.published).as("MISSING").hasSize(1);

        f.service.record(batch(ev(1L, null, ReceiptKind.RETURNED, 1, false, null)));
        assertThat(f.published).as("RETURNED").hasSize(2);

        GoodsReceiptDiscrepancyEvent last = (GoodsReceiptDiscrepancyEvent) f.published.get(1);
        assertThat(last.positionId()).isEqualTo(1L);
        assertThat(last.projectId()).isEqualTo(PROJECT);
        assertThat(last.actorUserId()).isEqualTo(USER);

        f.service.voidReceipt(f.receipts.get(0).getId(), new GoodsReceiptVoidRequest("x"));
        assertThat(f.published).as("void").hasSize(2);
    }

    @Test
    void surplusAcceptedPublishesOneEvent() {
        Fixture f = simple();
        f.service.record(batch(accepted(1L, 4)));
        assertThat(f.published).hasSize(1);
        assertThat(f.service.ledger(1L).ledger().surplus()).isEqualByComparingTo("1");
    }

    // ---------------------------------------------------------------------------------------------
    // Property 9: idempotent retry (Requirement 4.8)
    // ---------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<List<GoodsReceiptEventRequest>> validishEvents() {
        Arbitrary<GoodsReceiptEventRequest> event = Arbitraries.of(
                ev(1L, null, ReceiptKind.ACCEPTED, 1, false, null),
                ev(1L, null, ReceiptKind.ACCEPTED, 2, false, null),
                ev(1L, null, ReceiptKind.MISSING, 1, false, null),
                ev(1L, null, ReceiptKind.RETURNED, 1, false, null),
                ev(1L, null, ReceiptKind.RETURNED, 1, true, null));
        return event.list().ofMinSize(1).ofMaxSize(3);
    }

    /** Gives every event of the batch a fresh client request id. */
    private static GoodsReceiptRequest withIds(List<GoodsReceiptEventRequest> events) {
        List<GoodsReceiptEventRequest> out = new ArrayList<>();
        for (GoodsReceiptEventRequest e : events) {
            out.add(new GoodsReceiptEventRequest(e.positionId(), e.shipmentId(), e.kind(), e.quantity(),
                    e.fromAccepted(), e.comment(), UUID.randomUUID()));
        }
        return new GoodsReceiptRequest(out);
    }

    /** Feature: goods-receipt, Property 9: Idempotent retry. Validates: Requirements 4.8 */
    @Property(tries = 60)
    void retryWithSameClientRequestIdsChangesNothing(@ForAll("validishEvents") List<GoodsReceiptEventRequest> events) {
        Fixture f = simple();
        GoodsReceiptRequest request = withIds(events);
        GoodsReceiptBatchResult first;
        try {
            first = f.service.record(request);
        } catch (ForemenApiException e) {
            Assume.that(false); // only valid batches are in scope
            return;
        }
        List<Long> rowIds = f.receipts.stream().map(GoodsReceiptEntity::getId).toList();
        List<BigDecimal> quantities = f.receipts.stream().map(GoodsReceiptEntity::getQuantity).toList();
        int published = f.published.size();
        int redeliveries = f.redeliveries.size();
        int shipments = f.shipments.size();
        int closed = f.closed.size();
        Ledger ledger = f.ledgerService.ledgers(List.of(1L)).get(1L);
        PositionStatus status = f.positions.get(1L).getStatus();
        BigDecimal acceptedQuantity = f.positions.get(1L).getAcceptedQuantity();

        GoodsReceiptBatchResult second = f.service.record(request);

        assertThat(f.receipts.stream().map(GoodsReceiptEntity::getId).toList()).isEqualTo(rowIds);
        assertThat(f.receipts.stream().map(GoodsReceiptEntity::getQuantity).toList()).isEqualTo(quantities);
        assertThat(f.published).hasSize(published);
        assertThat(f.redeliveries).hasSize(redeliveries);
        assertThat(f.shipments).hasSize(shipments);
        assertThat(f.closed).hasSize(closed);
        assertThat(f.positions.get(1L).getStatus()).isEqualTo(status);
        assertThat(f.positions.get(1L).getAcceptedQuantity()).isEqualByComparingTo(acceptedQuantity);
        assertSame(f.ledgerService.ledgers(List.of(1L)).get(1L), ledger);
        assertThat(second.receipts()).extracting(GoodsReceiptDto::id)
                .containsExactlyElementsOf(first.receipts().stream().map(GoodsReceiptDto::id).toList());
        assertThat(second.positions()).isEqualTo(first.positions());
    }

    @Test
    void retryOfSplitEventReturnsAllPartsWithoutNewRows() {
        Fixture f = new Fixture();
        f.position(1L, PROJECT, 4, PositionStatus.IN_DELIVERY);
        f.shipment(100L, PROJECT, LocalDate.of(2026, 3, 4), 1L, 2);
        f.shipment(101L, PROJECT, LocalDate.of(2026, 3, 5), 1L, 2);
        GoodsReceiptRequest request = batch(ev(1L, null, ReceiptKind.ACCEPTED, 3, false, UUID.randomUUID()));

        GoodsReceiptBatchResult first = f.service.record(request);
        GoodsReceiptBatchResult second = f.service.record(request);

        assertThat(first.receipts()).hasSize(2);
        assertThat(second.receipts()).extracting(GoodsReceiptDto::id)
                .containsExactlyElementsOf(first.receipts().stream().map(GoodsReceiptDto::id).toList());
        assertThat(f.receipts).hasSize(2);
    }

    // ---------------------------------------------------------------------------------------------
    // Property 10: batch atomicity (Requirements 4.1, 4.2)
    // ---------------------------------------------------------------------------------------------

    enum Invalid { ZERO_QUANTITY, MISSING_NO_COMMENT, EXCEEDS_IN_FLIGHT, FLAG_ON_ACCEPTED, RETURN_EXCEEDS_ACCEPTED,
        NOT_RECEIVABLE_STATUS, UNKNOWN_POSITION }

    private static GoodsReceiptEventRequest invalidEvent(Invalid kind) {
        return switch (kind) {
            case ZERO_QUANTITY -> ev(1L, null, ReceiptKind.ACCEPTED, 0, false, null);
            case MISSING_NO_COMMENT -> new GoodsReceiptEventRequest(1L, null, ReceiptKind.MISSING,
                    BigDecimal.ONE, false, "  ", null);
            case EXCEEDS_IN_FLIGHT -> ev(1L, null, ReceiptKind.MISSING, 99, false, null);
            case FLAG_ON_ACCEPTED -> ev(1L, null, ReceiptKind.ACCEPTED, 1, true, null);
            case RETURN_EXCEEDS_ACCEPTED -> ev(1L, null, ReceiptKind.RETURNED, 99, true, null);
            case NOT_RECEIVABLE_STATUS -> ev(2L, null, ReceiptKind.ACCEPTED, 1, false, null);
            case UNKNOWN_POSITION -> ev(999L, null, ReceiptKind.ACCEPTED, 1, false, null);
        };
    }

    /** Feature: goods-receipt, Property 10: Batch atomicity. Validates: Requirements 4.1, 4.2 */
    @Property(tries = 70)
    void invalidEventInBatchLeavesNoCommittedEffect(@ForAll Invalid invalid,
                                                    @ForAll("prefixLength") int validPrefix) {
        Fixture f = simple();
        f.position(2L, PROJECT, 3, PositionStatus.ORDERED);
        List<GoodsReceiptEventRequest> events = new ArrayList<>();
        for (int i = 0; i < validPrefix; i++) {
            events.add(accepted(1L, 1));
        }
        events.add(invalidEvent(invalid));

        assertThatThrownBy(() -> f.tx(() -> f.service.record(new GoodsReceiptRequest(events))))
                .isInstanceOf(ForemenApiException.class);

        // after the simulated rollback the stored state is exactly the initial state
        assertThat(f.receipts).isEmpty();
        assertThat(f.lines).hasSize(1);

        // Everything that is not rolled back by the database is untouched: the exception reaches the
        // @Transactional boundary (checked below) before the position, shipment and notification effects.
        assertThat(f.positions.get(1L).getAcceptedQuantity()).isEqualByComparingTo("0");
        assertThat(f.positions.get(1L).getStatus()).isEqualTo(PositionStatus.IN_DELIVERY);
        assertThat(f.positions.get(2L).getStatus()).isEqualTo(PositionStatus.ORDERED);
        assertThat(f.transitions).isEmpty();
        assertThat(f.published).isEmpty();
        assertThat(f.redeliveries).isEmpty();
        assertThat(f.closed).isEmpty();
        assertThat(f.cancelled).isEmpty();
        assertThat(f.shipments).hasSize(1);
    }

    @Provide
    Arbitrary<Integer> prefixLength() {
        return Arbitraries.integers().between(0, 2);
    }

    @Test
    void recordAndVoidRunInOneTransactionThatRollsBackOnAnyRuntimeException() throws Exception {
        for (String name : List.of("record", "voidReceipt")) {
            Method m = java.util.Arrays.stream(GoodsReceiptService.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            Transactional tx = m.getAnnotation(Transactional.class);
            assertThat(tx).as(name).isNotNull();
            assertThat(tx.readOnly()).isFalse();
            assertThat(tx.noRollbackFor()).isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Property 5: void equals removal; acceptedQuantity in step with the ledger (Requirements 3.3, 5.1, 5.3, 5.4)
    // ---------------------------------------------------------------------------------------------

    /** Positive n records an event of the n-th kind, negative n voids the (-n)-th recorded row. */
    @Provide
    Arbitrary<List<Integer>> operations() {
        return Arbitraries.integers().between(-4, 5).list().ofMinSize(2).ofMaxSize(10);
    }

    private static GoodsReceiptEventRequest eventFor(int code) {
        return switch (code) {
            case 1 -> accepted(1L, 1);
            case 2 -> accepted(1L, 2);
            case 3 -> ev(1L, null, ReceiptKind.MISSING, 1, false, null);
            case 4 -> ev(1L, null, ReceiptKind.RETURNED, 1, false, null);
            default -> ev(1L, null, ReceiptKind.RETURNED, 1, true, null);
        };
    }

    /** Feature: goods-receipt, Property 5: Void equals removal. Validates: Requirements 3.3, 5.1, 5.3, 5.4 */
    @Property(tries = 80)
    void voidEqualsRemovalAndAcceptedQuantityFollowsLedger(@ForAll("operations") List<Integer> operations) {
        Fixture f = simple();
        for (int op : operations) {
            try {
                if (op > 0) {
                    f.tx(() -> f.service.record(batch(eventFor(op))));
                } else if (op < 0 && -op <= f.receipts.size()) {
                    Long target = f.receipts.get(-op - 1).getId();
                    f.tx(() -> f.service.voidReceipt(target, new GoodsReceiptVoidRequest("r")));
                }
            } catch (ForemenApiException rejected) {
                // a rejected operation must leave the invariants intact, checked below
            }
            Ledger ledger = f.ledgerService.ledgers(List.of(1L)).get(1L);
            assertThat(f.positions.get(1L).getAcceptedQuantity())
                    .as("acceptedQuantity vs ledger").isEqualByComparingTo(ledger.acceptedNet());
            assertThat(ledger.acceptedNet()).as("ledger vs store").isEqualByComparingTo(f.acceptedNetFromStore(1L));
            assertThat(ledger.acceptedNet().signum()).isGreaterThanOrEqualTo(0);
        }

        // void == removal: a world holding only the surviving rows has the same ledger
        Fixture clean = simple();
        for (GoodsReceiptEntity r : f.active()) {
            clean.rawRow(1L, r.getShipment() == null ? null : r.getShipment().getId(), r.getKind(),
                    r.getQuantity().intValue(), r.isFromAccepted());
        }
        Ledger voidedWorld = f.ledgerService.ledgers(List.of(1L)).get(1L);
        Ledger removedWorld = clean.ledgerService.ledgers(List.of(1L)).get(1L);
        // the recorded world may hold re-delivery lines the clean world lacks: compare event-driven parts
        assertThat(voidedWorld.acceptedNet()).isEqualByComparingTo(removedWorld.acceptedNet());
        assertThat(voidedWorld.missing()).isEqualByComparingTo(removedWorld.missing());
        assertThat(voidedWorld.returned()).isEqualByComparingTo(removedWorld.returned());
        assertThat(voidedWorld.outstanding()).isEqualByComparingTo(removedWorld.outstanding());
        assertThat(voidedWorld.surplus()).isEqualByComparingTo(removedWorld.surplus());
    }

    @Test
    void secondVoidOfSameEventChangesNothing() {
        Fixture f = simple();
        f.service.record(batch(accepted(1L, 2), accepted(1L, 1)));
        Long id = f.receipts.get(0).getId();
        f.service.voidReceipt(id, new GoodsReceiptVoidRequest("a"));
        Ledger after = f.ledgerService.ledgers(List.of(1L)).get(1L);

        assertApi(() -> f.service.voidReceipt(id, new GoodsReceiptVoidRequest("b")),
                HttpStatus.CONFLICT, "error.goodsReceipt.alreadyVoided");

        assertSame(f.ledgerService.ledgers(List.of(1L)).get(1L), after);
        assertThat(f.positions.get(1L).getAcceptedQuantity()).isEqualByComparingTo("1");
    }
}
