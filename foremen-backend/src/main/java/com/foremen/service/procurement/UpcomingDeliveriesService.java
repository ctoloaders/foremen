package com.foremen.service.procurement;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.controller.model.UpcomingDeliveryDto;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.shipment.ShipmentRules;
import com.foremen.service.procurement.shipment.TrackingBackoff;
import com.foremen.service.procurement.shipment.tracking.CarrierTrackingRegistry;
import com.foremen.service.procurement.access.ProcurementProjectAccess;

import jakarta.persistence.EntityManager;

/**
 * FOR-11-00 (R23): planned deliveries within the next {@code days} days (plus overdue ones), read with a
 * single query. Stages WITH_BUYER .. PARTIALLY_RECEIVED with a non-null {@code plannedDeliveryDate} on or
 * before today + days; project scoping through {@link ProcurementProjectAccess} (ADMIN: all).
 */
@Service
public class UpcomingDeliveriesService {

    public static final int DEFAULT_DAYS = 14;
    public static final int MAX_DAYS = 60;
    static final String DAYS_INVALID = "error.purchasePosition.upcoming.days.invalid";

    /** The five planned-delivery stages (R23.2). */
    static final Set<PositionStatus> STAGES = EnumSet.of(PositionStatus.WITH_BUYER, PositionStatus.ORDERED,
            PositionStatus.SUPPLIER_PAID, PositionStatus.IN_DELIVERY, PositionStatus.PARTIALLY_RECEIVED);

    private final EntityManager entityManager;
    private final ProcurementProjectAccess projectAccess;
    private final CarrierTrackingRegistry trackingRegistry;
    private final PurchaseRequestOverdue clockSupport;

    public UpcomingDeliveriesService(EntityManager entityManager, ProcurementProjectAccess projectAccess,
                                     CarrierTrackingRegistry trackingRegistry, Clock clock) {
        this.entityManager = entityManager;
        this.projectAccess = projectAccess;
        this.trackingRegistry = trackingRegistry;
        this.clockSupport = new PurchaseRequestOverdue(clock);
    }

    @Transactional(readOnly = true)
    public List<UpcomingDeliveryDto> upcoming(Integer days, Long projectId) {
        return upcoming(days, projectId, null);
    }

    /** As above; a non-null {@code shipmentId} keeps only the positions on that shipment (R12.5). */
    @Transactional(readOnly = true)
    public List<UpcomingDeliveryDto> upcoming(Integer days, Long projectId, Long shipmentId) {
        int window = days == null ? DEFAULT_DAYS : days;
        if (window < 1 || window > MAX_DAYS) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, DAYS_INVALID);
        }
        Set<Long> projectIds = scopedProjectIds(projectId);
        if (projectIds != null && projectIds.isEmpty()) {
            return List.of();
        }
        LocalDate today = clockSupport.today();
        LocalDate limit = today.plusDays(window);

        StringBuilder jpql = new StringBuilder("SELECT p FROM PurchasePositionEntity p "
                + "JOIN FETCH p.project pr "
                + "JOIN FETCH p.plannedMaterial pm LEFT JOIN FETCH pm.producer "
                + "LEFT JOIN FETCH p.actualMaterial am LEFT JOIN FETCH am.producer "
                + "JOIN FETCH p.unit u "
                + "LEFT JOIN FETCH p.purchaseRequest "
                + "WHERE p.status IN :stages AND p.plannedDeliveryDate IS NOT NULL "
                + "AND p.plannedDeliveryDate <= :limit ");
        if (shipmentId != null) {
            jpql.append("AND p.id IN (SELECT sl.position.id FROM ShipmentLineEntity sl "
                    + "WHERE sl.shipment.id = :shipmentId) ");
        }
        if (projectIds != null) {
            jpql.append("AND pr.id IN :projectIds ");
        }
        jpql.append("ORDER BY p.plannedDeliveryDate, pr.name, p.id");
        var query = entityManager.createQuery(jpql.toString(), PurchasePositionEntity.class)
                .setParameter("stages", STAGES)
                .setParameter("limit", limit);
        if (projectIds != null) {
            query.setParameter("projectIds", projectIds);
        }
        if (shipmentId != null) {
            query.setParameter("shipmentId", shipmentId);
        }
        boolean pl = !"ru".equalsIgnoreCase(LocaleContextHolder.getLocale().getLanguage());
        List<PurchasePositionEntity> positions = query.getResultList();
        Map<Long, List<ShipmentEntity>> shipments = activeShipments(positions);
        return positions.stream()
                .map(p -> toDto(p, today, pl, shipments.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    /** One batch query: Active_Shipments (not DELIVERED / CANCELLED) of the result positions (R12.3). */
    private Map<Long, List<ShipmentEntity>> activeShipments(List<PurchasePositionEntity> positions) {
        if (positions.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = positions.stream().map(PurchasePositionEntity::getId).toList();
        List<Object[]> rows = entityManager.createQuery(
                        "SELECT sl.position.id, s FROM ShipmentLineEntity sl JOIN sl.shipment s "
                                + "WHERE sl.position.id IN :ids AND s.status NOT IN :closed", Object[].class)
                .setParameter("ids", ids)
                .setParameter("closed", EnumSet.of(ShipmentStatus.DELIVERED, ShipmentStatus.CANCELLED))
                .getResultList();
        Map<Long, List<ShipmentEntity>> byPosition = new HashMap<>();
        for (Object[] row : rows) {
            byPosition.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add((ShipmentEntity) row[1]);
        }
        return byPosition;
    }

    /** R12.2: earliest ETA first, shipments without an ETA last, ties by id; {@code null} when none. */
    static ShipmentEntity nearest(List<ShipmentEntity> active) {
        return active.stream()
                .min(Comparator.comparing(ShipmentEntity::getEtaDate,
                                Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                        .thenComparing(ShipmentEntity::getId))
                .orElse(null);
    }

    private String trackingPageUrl(ShipmentEntity s) {
        if (s.getDeliveryMethod() != DeliveryMethod.CARRIER || s.getCarrierCode() == null) {
            return null;
        }
        if (ShipmentRules.OTHER_CARRIER.equals(s.getCarrierCode())) {
            return s.getTrackingUrl();
        }
        if (s.getTrackingNumber() == null) {
            return null;
        }
        return trackingRegistry.findAny(s.getCarrierCode())
                .map(a -> a.trackingPageUrl(s.getTrackingNumber()))
                .orElse(null);
    }

    /** {@code null} = unrestricted (ADMIN without a project filter); otherwise the ids the caller may see. */
    private Set<Long> scopedProjectIds(Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (isAdmin(auth)) {
            return projectId == null ? null : Set.of(projectId);
        }
        Long userId = userId(auth);
        Set<Long> allowed = new HashSet<>(projectAccess.allowedProjectIds(userId));
        if (projectId != null) {
            allowed.retainAll(Set.of(projectId));
        }
        return allowed;
    }

    private UpcomingDeliveryDto toDto(PurchasePositionEntity p, LocalDate today, boolean pl,
                                      List<ShipmentEntity> active) {
        ShipmentEntity s = nearest(active);
        FinishingMaterialEntity m = p.getActualMaterial() != null ? p.getActualMaterial() : p.getPlannedMaterial();
        String producer = m.getProducer() == null ? null
                : pl ? m.getProducer().getNamePL() : m.getProducer().getNameRU();
        String unitName = pl ? p.getUnit().getNamePL() : p.getUnit().getNameRU();
        return new UpcomingDeliveryDto(
                p.getId(), p.getProject().getId(), p.getProject().getName(), m.getModel(), producer,
                p.getQuantity(), unitName, p.getPlannedDeliveryDate(), p.getStatus(),
                p.getPlannedDeliveryDate().isBefore(today),
                p.getPurchaseRequest() == null ? null : p.getPurchaseRequest().getId(),
                s == null ? null : s.getId(),
                s == null ? null : s.getStatus(),
                s == null ? null : s.getDeliveryMethod(),
                s == null ? null : s.getCarrierCode(),
                s == null ? null : s.getEtaDate(),
                s == null ? null : s.getEtaTimeFrom(),
                s == null ? null : s.getEtaTimeTo(),
                s == null ? null : s.getDriverName(),
                s == null ? null : s.getDriverPhone(),
                s == null ? null : trackingPageUrl(s),
                s == null ? null : s.getShopOrderLink(),
                s == null ? null : active.size(),
                s == null ? null : TrackingBackoff.isDegraded(s.getConsecutiveFailures()));
    }

    private static boolean isAdmin(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(ga.getAuthority()) || "ADMIN".equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static Long userId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        try {
            return Long.parseLong(auth.getName().trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
