package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.foremen.dao.RoomDao;
import com.foremen.dao.WorkPackageOverrideDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkPackageOverrideEntity;

/**
 * Resolves the package pricing of an estimate (FOR-05-04 additional tasks): when the project has an
 * applied package ({@code project.packageApplied} + {@code project.offerPackage}) the net total is
 * {@code package price per m2 x apartment area + extra works beyond the package at the price list}
 * (see {@link EstimatePackagePricing}). Shared by the matrix header and the offer totals so both
 * report the same number.
 *
 * <ul>
 *   <li>Apartment area = the sum of the project's room floor areas.</li>
 *   <li>Works covered by the package = the works with a member {@code WorkPackageOverride} row for
 *       the package; every other assigned work line is an extra, priced at its effective line net.</li>
 * </ul>
 */
@Component
public class EstimatePackagePricingService {

    /** Synthetic key (never a real line id) of the package subtotal in {@link #clientPricesOf}. */
    public static final Long PACKAGE_PRICE_KEY = -1L;

    private final RoomDao roomDao;
    private final WorkPackageOverrideDao workPackageOverrideDao;

    public EstimatePackagePricingService(RoomDao roomDao, WorkPackageOverrideDao workPackageOverrideDao) {
        this.roomDao = roomDao;
        this.workPackageOverrideDao = workPackageOverrideDao;
    }

    /**
     * The package pricing of {@code estimate}, or empty when its project has no applied package.
     * Must be called inside a transaction (lazy project / package / lines are traversed).
     */
    public Optional<EstimatePackagePricing.Result> pricingOf(EstimateEntity estimate) {
        ProjectEntity project = estimate == null ? null : estimate.getProject();
        if (project == null || !project.isPackageApplied() || project.getOfferPackage() == null) {
            return Optional.empty();
        }
        OfferPackageEntity offerPackage = project.getOfferPackage();
        Set<Long> memberWorkIds = memberWorkIds(offerPackage.getId());
        List<BigDecimal> extras = new ArrayList<>();
        if (estimate.getLines() != null) {
            for (EstimateLineEntity line : estimate.getLines()) {
                if (line == null || line.getWorkItem() == null) {
                    continue;
                }
                if (!memberWorkIds.contains(line.getWorkItem().getId())) {
                    extras.add(line.effectiveValueNet());
                }
            }
        }
        return Optional.of(EstimatePackagePricing.compute(
                offerPackage.getZlM2(), apartmentArea(project.getId()), extras));
    }

    /**
     * The estimate's client-facing net per line for the OFFER totals when a package is applied:
     * the extra works keyed by estimate-line id plus one synthetic {@link #PACKAGE_PRICE_KEY} entry
     * carrying the package subtotal (covered works are folded into it). Empty when no package is
     * applied, in which case the caller keeps the price-list per-line prices. The sum of the values
     * equals {@link EstimatePackagePricing.Result#total()}, so offer and estimate totals agree.
     */
    public Optional<java.util.Map<Long, BigDecimal>> clientPricesOf(EstimateEntity estimate) {
        Optional<EstimatePackagePricing.Result> pricing = pricingOf(estimate);
        if (pricing.isEmpty()) {
            return Optional.empty();
        }
        Set<Long> covered = memberWorkIds(estimate.getProject().getOfferPackage().getId());
        java.util.Map<Long, BigDecimal> prices = new java.util.HashMap<>();
        if (estimate.getLines() != null) {
            for (EstimateLineEntity line : estimate.getLines()) {
                if (line != null && line.getId() != null && line.getWorkItem() != null
                        && !covered.contains(line.getWorkItem().getId())) {
                    prices.put(line.getId(), line.effectiveValueNet());
                }
            }
        }
        prices.put(PACKAGE_PRICE_KEY, pricing.get().packageSubtotal());
        return Optional.of(prices);
    }

    /** Ids of the works covered (member) by the package. */
    public Set<Long> memberWorkIds(Long offerPackageId) {
        Set<Long> ids = new HashSet<>();
        for (WorkPackageOverrideEntity override : workPackageOverrideDao.findAllWithWorkItemAndPackage()) {
            if (override.getOfferPackage() != null
                    && offerPackageId != null
                    && offerPackageId.equals(override.getOfferPackage().getId())
                    && Boolean.TRUE.equals(override.getMember())
                    && override.getWorkItem() != null) {
                ids.add(override.getWorkItem().getId());
            }
        }
        return ids;
    }

    /** The apartment area: the sum of the project's room floor areas. */
    public BigDecimal apartmentArea(Long projectId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (RoomEntity room : roomDao.findByProjectId(projectId)) {
            if (room.getFloorArea() != null && room.getFloorArea().signum() > 0) {
                sum = sum.add(room.getFloorArea());
            }
        }
        return sum;
    }
}
