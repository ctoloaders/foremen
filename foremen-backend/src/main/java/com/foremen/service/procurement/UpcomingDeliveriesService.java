package com.foremen.service.procurement;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
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
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.exception.ForemenApiException;
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
    private final PurchaseRequestOverdue clockSupport;

    public UpcomingDeliveriesService(EntityManager entityManager, ProcurementProjectAccess projectAccess,
                                     Clock clock) {
        this.entityManager = entityManager;
        this.projectAccess = projectAccess;
        this.clockSupport = new PurchaseRequestOverdue(clock);
    }

    @Transactional(readOnly = true)
    public List<UpcomingDeliveryDto> upcoming(Integer days, Long projectId) {
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
        boolean pl = !"ru".equalsIgnoreCase(LocaleContextHolder.getLocale().getLanguage());
        return query.getResultList().stream().map(p -> toDto(p, today, pl)).toList();
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

    private static UpcomingDeliveryDto toDto(PurchasePositionEntity p, LocalDate today, boolean pl) {
        FinishingMaterialEntity m = p.getActualMaterial() != null ? p.getActualMaterial() : p.getPlannedMaterial();
        String producer = m.getProducer() == null ? null
                : pl ? m.getProducer().getNamePL() : m.getProducer().getNameRU();
        String unitName = pl ? p.getUnit().getNamePL() : p.getUnit().getNameRU();
        return new UpcomingDeliveryDto(
                p.getId(), p.getProject().getId(), p.getProject().getName(), m.getModel(), producer,
                p.getQuantity(), unitName, p.getPlannedDeliveryDate(), p.getStatus(),
                p.getPlannedDeliveryDate().isBefore(today),
                p.getPurchaseRequest() == null ? null : p.getPurchaseRequest().getId());
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
