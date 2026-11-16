package com.foremen.service.procurement.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.controller.model.GoodsReceiptDelegationDto;
import com.foremen.dao.GoodsReceiptDao;
import com.foremen.dao.GoodsReceiptDelegationDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.ShipmentDao;
import com.foremen.dao.ShipmentLineDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.DelegationStatus;
import com.foremen.dao.model.GoodsReceiptDelegationEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.access.ProcurementProjectAccess;

import jakarta.persistence.EntityManager;

/**
 * FOR-11-06 task 8.3 - rules of {@link GoodsReceiptDelegationService}: assignee must be an ACTIVE project WORKER,
 * one ACTIVE delegation per shipment, reassign, revoke, completion, caller checks and event emission.
 *
 * <p>Validates: Requirements 12.1, 12.2, 12.3, 12.7, 12.8
 */
class GoodsReceiptDelegationServiceTest {

    private static final long PROJECT = 10L;
    private static final long SHIPMENT = 100L;
    private static final long MANAGER = 1L;
    private static final long WORKER_A = 21L;
    private static final long WORKER_B = 22L;

    private final GoodsReceiptDelegationDao delegationDao = mock(GoodsReceiptDelegationDao.class);
    private final ShipmentDao shipmentDao = mock(ShipmentDao.class);
    private final ShipmentLineDao lineDao = mock(ShipmentLineDao.class);
    private final GoodsReceiptDao receiptDao = mock(GoodsReceiptDao.class);
    private final ProjectMemberDao memberDao = mock(ProjectMemberDao.class);
    private final UserDao userDao = mock(UserDao.class);
    private final ProcurementProjectAccess access = mock(ProcurementProjectAccess.class);
    private final EntityManager em = mock(EntityManager.class);
    private final List<Object> published = new ArrayList<>();
    private final List<GoodsReceiptDelegationEntity> stored = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(500);
    private GoodsReceiptDelegationService service;
    private ShipmentEntity shipment;

    @BeforeEach
    void setUp() {
        service = new GoodsReceiptDelegationService(delegationDao, shipmentDao, lineDao, receiptDao, memberDao,
                userDao, access, em, published::add, Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC));
        shipment = new ShipmentEntity();
        shipment.setId(SHIPMENT);
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT);
        shipment.setProject(project);
        shipment.setStatus(ShipmentStatus.IN_TRANSIT);
        when(shipmentDao.findById(SHIPMENT)).thenReturn(Optional.of(shipment));
        when(lineDao.findByShipmentId(anyLong())).thenReturn(List.of());
        when(access.allowedProjectIds(anyLong())).thenReturn(Set.of(PROJECT));
        when(userDao.findById(anyLong())).thenAnswer(inv -> Optional.of(user(inv.getArgument(0))));
        member(MANAGER, "MANAGER", AssignmentStatus.ACTIVE);
        member(WORKER_A, "WORKER", AssignmentStatus.ACTIVE);
        member(WORKER_B, "WORKER", AssignmentStatus.ACTIVE);
        when(delegationDao.save(any(GoodsReceiptDelegationEntity.class))).thenAnswer(inv -> {
            GoodsReceiptDelegationEntity d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(ids.incrementAndGet());
            }
            if (!stored.contains(d)) {
                stored.add(d);
            }
            return d;
        });
        when(delegationDao.findByShipmentIdAndStatus(anyLong(), any(DelegationStatus.class))).thenAnswer(
                inv -> stored.stream().filter(d -> d.getShipmentId().equals(inv.getArgument(0))
                        && d.getStatus() == inv.getArgument(1)).findFirst());
        when(delegationDao.findById(anyLong())).thenAnswer(inv -> stored.stream()
                .filter(d -> d.getId().equals(inv.getArgument(0))).findFirst());
        caller(MANAGER, "MANAGER");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static UserEntity user(Long id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setName("user-" + id);
        return u;
    }

    private void member(long userId, String role, AssignmentStatus status) {
        ProjectMemberEntity m = new ProjectMemberEntity();
        m.setUser(user(userId));
        m.setProjectId(PROJECT);
        RoleEntity r = new RoleEntity();
        r.setCode(role);
        m.setProjectRole(r);
        m.setAssignmentStatus(status);
        when(memberDao.findByUserIdAndProjectId(userId, PROJECT)).thenReturn(Optional.of(m));
    }

    private static void caller(long id, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(id), "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static void assertApi(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ForemenApiException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(status.value());
            assertThat(e.getMessageCode()).isEqualTo(code);
        });
    }

    // ---- R12.2: assignee ----

    @Test
    void delegatesToActiveProjectWorkerAndPublishesDelegatedEvent() {
        GoodsReceiptDelegationDto dto = service.delegate(SHIPMENT, WORKER_A);

        assertThat(dto.status()).isEqualTo(DelegationStatus.ACTIVE);
        assertThat(dto.assigneeId()).isEqualTo(WORKER_A);
        assertThat(dto.delegatedById()).isEqualTo(MANAGER);
        assertThat(published).containsExactly(
                new ReceiptDelegatedEvent(dto.id(), SHIPMENT, PROJECT, WORKER_A, null, MANAGER));
    }

    @Test
    void assigneeWhoIsNotAProjectWorkerIsRejectedWithNoDelegationAndNoEvent() {
        member(WORKER_A, "FOREMAN", AssignmentStatus.ACTIVE);
        assertApi(() -> service.delegate(SHIPMENT, WORKER_A), HttpStatus.BAD_REQUEST,
                "error.goodsReceipt.delegation.assigneeInvalid");

        member(WORKER_A, "WORKER", AssignmentStatus.INACTIVE);
        assertApi(() -> service.delegate(SHIPMENT, WORKER_A), HttpStatus.BAD_REQUEST,
                "error.goodsReceipt.delegation.assigneeInvalid");

        when(memberDao.findByUserIdAndProjectId(99L, PROJECT)).thenReturn(Optional.empty());
        assertApi(() -> service.delegate(SHIPMENT, 99L), HttpStatus.BAD_REQUEST,
                "error.goodsReceipt.delegation.assigneeInvalid");
        assertApi(() -> service.delegate(SHIPMENT, null), HttpStatus.BAD_REQUEST,
                "error.goodsReceipt.delegation.assigneeInvalid");

        assertThat(stored).isEmpty();
        assertThat(published).isEmpty();
    }

    // ---- R12.1, R12.2: one ACTIVE per shipment, reassign ----

    @Test
    void reassignRevokesPreviousAndKeepsExactlyOneActive() {
        GoodsReceiptDelegationDto first = service.delegate(SHIPMENT, WORKER_A);
        GoodsReceiptDelegationDto second = service.delegate(SHIPMENT, WORKER_B);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(stored).filteredOn(d -> d.getStatus() == DelegationStatus.ACTIVE)
                .singleElement().satisfies(d -> assertThat(d.getAssignee().getId()).isEqualTo(WORKER_B));
        GoodsReceiptDelegationEntity old = stored.stream().filter(d -> d.getId().equals(first.id())).findFirst()
                .orElseThrow();
        assertThat(old.getStatus()).isEqualTo(DelegationStatus.REVOKED);
        assertThat(old.getRevokedAt()).isNotNull();
        assertThat(old.getRevokedBy().getId()).isEqualTo(MANAGER);
        assertThat(published.get(1)).isEqualTo(
                new ReceiptDelegatedEvent(second.id(), SHIPMENT, PROJECT, WORKER_B, WORKER_A, MANAGER));
    }

    // ---- R12.2: closed shipments ----

    @Test
    void cancelledShipmentIsRefusedWith409() {
        shipment.setStatus(ShipmentStatus.CANCELLED);
        assertApi(() -> service.delegate(SHIPMENT, WORKER_A), HttpStatus.CONFLICT,
                "error.goodsReceipt.delegation.shipmentClosed");
        assertThat(stored).isEmpty();
    }

    @Test
    void shipmentFullyReceivedIsRefusedWith409() {
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setId(1L);
        ShipmentLineEntity l = new ShipmentLineEntity();
        l.setShipment(shipment);
        l.setPosition(p);
        l.setQuantity(BigDecimal.valueOf(3));
        when(lineDao.findByShipmentId(SHIPMENT)).thenReturn(List.of(l));
        // row: shipmentId, positionId, kind, quantity
        when(receiptDao.sumsByShipmentAndPosition(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[] {SHIPMENT, 1L, null, BigDecimal.valueOf(3)}));

        assertApi(() -> service.delegate(SHIPMENT, WORKER_A), HttpStatus.CONFLICT,
                "error.goodsReceipt.delegation.shipmentClosed");
    }

    // ---- caller rules ----

    @Test
    void callerWithoutManagerRoleInProjectIsForbidden() {
        member(MANAGER, "FOREMAN", AssignmentStatus.ACTIVE);
        assertApi(() -> service.delegate(SHIPMENT, WORKER_A), HttpStatus.FORBIDDEN, "error.access.denied");
        assertThat(stored).isEmpty();
    }

    @Test
    void shipmentOutsideCallerProjectsIsNotFound() {
        when(access.allowedProjectIds(MANAGER)).thenReturn(Set.of(999L));
        assertApi(() -> service.delegate(SHIPMENT, WORKER_A), HttpStatus.NOT_FOUND, "error.entity.not.found");
    }

    @Test
    void adminMayDelegateWithoutProjectMembership() {
        caller(7L, "ADMIN");
        when(access.allowedProjectIds(7L)).thenReturn(Set.of());

        assertThat(service.delegate(SHIPMENT, WORKER_A).status()).isEqualTo(DelegationStatus.ACTIVE);
    }

    // ---- R12.3, R12.8: revoke ----

    @Test
    void revokeMarksRevokedAndPublishesRevokedEvent() {
        GoodsReceiptDelegationDto d = service.delegate(SHIPMENT, WORKER_A);
        published.clear();

        GoodsReceiptDelegationDto revoked = service.revoke(d.id());

        assertThat(revoked.status()).isEqualTo(DelegationStatus.REVOKED);
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(published).containsExactly(
                new ReceiptDelegationRevokedEvent(d.id(), SHIPMENT, PROJECT, WORKER_A, MANAGER));
    }

    @Test
    void revokingANonActiveDelegationIsConflictAndEmitsNothing() {
        GoodsReceiptDelegationDto d = service.delegate(SHIPMENT, WORKER_A);
        service.revoke(d.id());
        published.clear();

        assertApi(() -> service.revoke(d.id()), HttpStatus.CONFLICT, "error.goodsReceipt.delegation.notActive");
        assertApi(() -> service.revoke(12345L), HttpStatus.NOT_FOUND, "error.entity.not.found");
        assertThat(published).isEmpty();
    }

    @Test
    void workerCannotRevoke() {
        GoodsReceiptDelegationDto d = service.delegate(SHIPMENT, WORKER_A);
        member(WORKER_A, "WORKER", AssignmentStatus.ACTIVE);
        caller(WORKER_A, "WORKER");

        assertApi(() -> service.revoke(d.id()), HttpStatus.FORBIDDEN, "error.access.denied");
    }

    // ---- R12.7: completion ----

    @Test
    void completeForShipmentCompletesTheActiveDelegationOnly() {
        GoodsReceiptDelegationDto d = service.delegate(SHIPMENT, WORKER_A);

        service.completeForShipment(SHIPMENT);

        GoodsReceiptDelegationEntity e = stored.get(0);
        assertThat(e.getId()).isEqualTo(d.id());
        assertThat(e.getStatus()).isEqualTo(DelegationStatus.COMPLETED);
        assertThat(e.getCompletedAt()).isNotNull();
    }

    @Test
    void completeForShipmentWithoutActiveDelegationIsANoOp() {
        service.completeForShipment(SHIPMENT);
        service.completeForShipment(null);

        verify(delegationDao, never()).save(any());
    }

    @Test
    void activeShipmentIdsForReadsTheAssigneesActiveDelegations() {
        when(delegationDao.findActiveShipmentIdsByAssignee(WORKER_A)).thenReturn(List.of(100L, 101L));

        assertThat(service.activeShipmentIdsFor(WORKER_A)).containsExactlyInAnyOrder(100L, 101L);
        assertThat(service.activeShipmentIdsFor(null)).isEmpty();
    }
}
