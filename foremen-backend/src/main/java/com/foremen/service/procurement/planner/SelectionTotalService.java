package com.foremen.service.procurement.planner;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.controller.model.SelectionTotalResponse;
import com.foremen.controller.model.SelectionTotalResponse.SkipReason;
import com.foremen.controller.model.SelectionTotalResponse.SkippedPosition;
import com.foremen.controller.model.SelectionTotalResponse.VatBreakdownItem;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.planner.PositionMoneyMath.Line;
import com.foremen.service.procurement.planner.PositionMoneyMath.Totals;
import com.foremen.service.procurement.position.PositionAudienceResolver;

/**
 * Tick-to-proforma total (FOR-11-01 R6). Read-only: classifies each requested position as counted or
 * skipped and sums the sale lines with {@link PositionMoneyMath}.
 *
 * <p>Order of checks: audience ({@code STAFF_FULL} only, else 403), size (1..500, else 400), then per
 * position: not loaded or outside the caller's projects {@code NOT_FOUND}, not {@code AGREED}
 * {@code NOT_AGREED}, not stage {@code ACCEPTED} {@code NOT_ACCEPTED}, no sale price {@code NO_SALE_PRICE}.
 * An inaccessible position is indistinguishable from a missing one.
 */
@Service
public class SelectionTotalService {

    public static final int MAX_IDS = 500;

    static final String EMPTY_MESSAGE = "error.purchasePlanner.selection.empty";
    static final String TOO_LARGE_MESSAGE = "error.purchasePlanner.selection.tooLarge";
    static final String ACCESS_DENIED_MESSAGE = "error.access.denied";

    private final PurchasePositionDao positionDao;
    private final ProcurementProjectAccess projectAccess;
    private final PositionAudienceResolver audienceResolver;

    public SelectionTotalService(PurchasePositionDao positionDao, ProcurementProjectAccess projectAccess,
                                 PositionAudienceResolver audienceResolver) {
        this.positionDao = positionDao;
        this.projectAccess = projectAccess;
        this.audienceResolver = audienceResolver;
    }

    @Transactional(readOnly = true)
    public SelectionTotalResponse total(List<Long> positionIds) {
        if (!audienceResolver.isStaffFull()) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, ACCESS_DENIED_MESSAGE);
        }
        if (positionIds == null || positionIds.isEmpty() || positionIds.stream().anyMatch(Objects::isNull)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, EMPTY_MESSAGE);
        }
        if (positionIds.size() > MAX_IDS) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, TOO_LARGE_MESSAGE);
        }

        List<Long> ids = List.copyOf(new LinkedHashSet<>(positionIds));
        Map<Long, PurchasePositionEntity> loaded = new LinkedHashMap<>();
        for (PurchasePositionEntity p : positionDao.findAllById(ids)) {
            loaded.put(p.getId(), p);
        }
        Scope scope = currentScope();

        List<SkippedPosition> skipped = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        Map<BigDecimal, List<Line>> byRate = new TreeMap<>(Comparator.nullsFirst(Comparator.<BigDecimal>naturalOrder()));
        Set<Long> projectIds = new LinkedHashSet<>();

        for (Long id : ids) {
            PurchasePositionEntity p = loaded.get(id);
            if (p == null || !scope.allows(p.getProject().getId())) {
                skipped.add(new SkippedPosition(id, SkipReason.NOT_FOUND));
            } else if (p.getSourcingPath() != SourcingPath.AGREED) {
                skipped.add(new SkippedPosition(id, SkipReason.NOT_AGREED));
            } else if (p.getStatus() != PositionStatus.ACCEPTED) {
                skipped.add(new SkippedPosition(id, SkipReason.NOT_ACCEPTED));
            } else if (p.getSalePrice() == null) {
                skipped.add(new SkippedPosition(id, SkipReason.NO_SALE_PRICE));
            } else {
                BigDecimal rate = p.getSaleVatRate() == null ? null : p.getSaleVatRate().getRate();
                Line line = PositionMoneyMath.saleLine(p.getSalePrice(), p.getQuantity(), rate);
                lines.add(line);
                byRate.computeIfAbsent(rate, r -> new ArrayList<>()).add(line);
                projectIds.add(p.getProject().getId());
            }
        }

        Totals totals = PositionMoneyMath.sum(lines);
        List<VatBreakdownItem> breakdown = new ArrayList<>();
        byRate.forEach((rate, rateLines) -> {
            Totals t = PositionMoneyMath.sum(rateLines);
            breakdown.add(new VatBreakdownItem(rate, t.net(), t.vat(), t.gross()));
        });
        Long projectId = projectIds.size() == 1 ? projectIds.iterator().next() : null;
        return new SelectionTotalResponse(lines.size(), totals.net(), totals.vat(), totals.gross(),
                breakdown, projectId, projectIds.size() > 1, skipped);
    }

    private Scope currentScope() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return new Scope(false, Set.of());
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(ga.getAuthority()) || "ADMIN".equals(ga.getAuthority())) {
                return new Scope(true, Set.of());
            }
        }
        try {
            Long userId = Long.parseLong(auth.getName().trim());
            Set<Long> allowed = projectAccess.allowedProjectIds(userId);
            return new Scope(false, allowed == null ? Set.of() : allowed);
        } catch (RuntimeException e) {
            return new Scope(false, Set.of());
        }
    }

    private record Scope(boolean all, Set<Long> projectIds) {
        boolean allows(Long projectId) {
            return all || projectIds.contains(projectId);
        }
    }
}
