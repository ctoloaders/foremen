package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.EstimateLinePackagePriceHistoryDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLinePackagePriceEntity;
import com.foremen.dao.model.EstimateLinePackagePriceHistoryEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkPackagePriceEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.pricing.EffectivePriceResolver;

/**
 * Copies the FOR-04-12b catalog per-package price onto a per-line-per-package project price
 * snapshot at add-time (FOR-05-03, Requirement 4; design §6.2 {@code snapshotForLine}).
 *
 * <p>A stateless Spring {@code @Component} that reads the already-seeded catalog (via
 * {@link OfferPackageDao} and {@link WorkPriceDao}) and the FOR-04-12b {@link EffectivePriceResolver}
 * MAX-fallback rule, mirroring the {@code EffectivePriceResolver} / {@code EstimateLineRoomQtyValidator}
 * convention of a small stateless service that performs its own minimal reads and holds no mutable
 * state. {@link #snapshotForLine} does not persist anything itself: {@code EstimateLineService}
 * (task 12.3) is responsible for saving the returned rows once the owning line is persisted, and
 * then calling {@link #captureHistory} per saved row to write its initial history entry —
 * {@link #captureHistory} itself IS the persisting building block (task 10.1), reused by both the
 * initial capture and every later price change.
 *
 * <p>For every {@link OfferPackageEntity} that exists <b>at add-time</b> (R4.1, R4.6), one
 * {@link EstimateLinePackagePriceEntity} is built:
 * <ul>
 *   <li>the copied value is resolved through {@link EffectivePriceResolver#resolve} over the work
 *       item's {@code WorkPackagePrice} collection (MAX-fallback, R4.3, R4.7);</li>
 *   <li>when present, {@code originalUnitPrice} is set to the resolved value, {@code unpriced=false},
 *       the {@code workPackagePrice} provenance FK is set to the matching catalog row (lineage only,
 *       R4.4), and the effective {@code unitPrice} equals {@code originalUnitPrice} (no discount yet
 *       on a fresh snapshot, R5.2);</li>
 *   <li>when absent, the row is left {@code unpriced=true} with both prices {@code null} — never a
 *       fabricated value (R4.7) — and the line is still created (R4.7, not a blocking condition).</li>
 * </ul>
 * Packages created after this call are never retroactively added to an existing line (R4.6, R12.7):
 * the snapshot reads {@code allOfferPackages()} exactly once, at the moment the line is added.
 */
@Component
public class PackagePriceSnapshotService {

    private final OfferPackageDao offerPackageDao;
    private final WorkPriceDao workPriceDao;
    private final EffectivePriceResolver effectivePriceResolver;
    private final EstimateLinePackagePriceHistoryDao historyDao;

    public PackagePriceSnapshotService(OfferPackageDao offerPackageDao,
                                        WorkPriceDao workPriceDao,
                                        EffectivePriceResolver effectivePriceResolver,
                                        EstimateLinePackagePriceHistoryDao historyDao) {
        this.offerPackageDao = offerPackageDao;
        this.workPriceDao = workPriceDao;
        this.effectivePriceResolver = effectivePriceResolver;
        this.historyDao = historyDao;
    }

    /**
     * Builds one {@link EstimateLinePackagePriceEntity} per {@link OfferPackageEntity} existing right
     * now, copying the catalog price for {@code line.workItem} via the MAX-fallback rule (R4.1–R4.3,
     * R4.7; design §6.2). The returned rows are transient (not yet persisted); the caller is expected
     * to save them alongside the just-created line.
     *
     * <p>The caller MUST persist each returned row first (so it has an id) and then call
     * {@link #captureHistory} on it to write the initial history snapshot (R6.2) — this method does
     * not call {@link #captureHistory} itself, since the rows it returns have no id yet.
     *
     * @param line the just-created {@link EstimateLineEntity}, with {@code workItem} resolved
     * @return one transient per-package price row for every offer package that exists at this
     *         moment, in package order; never {@code null}
     */
    @Transactional(readOnly = true)
    public List<EstimateLinePackagePriceEntity> snapshotForLine(EstimateLineEntity line) {
        List<EstimateLinePackagePriceEntity> results = new ArrayList<>();
        if (line == null || line.getWorkItem() == null) {
            return results;
        }

        // packages existing NOW (R4.1, R4.6) — read fresh, never cached, so a package created after
        // this call is simply not seen by an already-snapshotted line (R4.6, R12.7).
        List<OfferPackageEntity> packages = new ArrayList<>();
        offerPackageDao.findAll(Sort.by("id")).forEach(packages::add);

        List<WorkPriceEntity> workPrices =
                workPriceDao.findByWorkItemIdIn(List.of(line.getWorkItem().getId()));
        List<WorkPackagePriceEntity> workPackagePrices = workPrices.isEmpty()
                ? List.of()
                : workPrices.get(0).getPackagePrices();

        for (OfferPackageEntity pkg : packages) {
            EstimateLinePackagePriceEntity row = new EstimateLinePackagePriceEntity();
            row.setLine(line);
            row.setOfferPackage(pkg);

            Optional<BigDecimal> effective = effectivePriceResolver.resolve(workPackagePrices, pkg.getCode());
            if (effective.isPresent()) {
                row.setOriginalUnitPrice(effective.get());
                row.setUnpriced(false);
                row.setWorkPackagePrice(provenanceOf(workPackagePrices, pkg));
                // no discount yet on a fresh snapshot: effective unitPrice = originalUnitPrice (R5.2)
                row.setUnitPrice(effective.get());
            } else {
                row.setOriginalUnitPrice(null);   // R4.7: unpriced, never fabricated
                row.setUnpriced(true);
                row.setUnitPrice(null);
            }

            results.add(row);
        }

        return results;
    }

    /**
     * Builds and persists the append-only {@link EstimateLinePackagePriceHistoryEntity} snapshot for
     * {@code row} at capture time (R6.1, R6.2). A brand-new row is inserted on every call; prior
     * history rows for the same {@code packagePrice} are never updated or deleted (R6.3) — this
     * method never reuses or mutates an existing history row, so calling it repeatedly for the same
     * {@code row} simply appends one snapshot per call.
     *
     * <p>{@code changedBy} is resolved from the current security context (the authenticated
     * caller's username), mirroring {@code AdminService.resolveCurrentUser()}'s convention, falling
     * back to {@code "SYSTEM"} when there is no authenticated caller (e.g. a system-triggered
     * change).
     *
     * <p>Exposed as {@code public} so both the initial capture right after a line is added (task
     * 12.3's {@code EstimateLineService}, following {@link #snapshotForLine}) and every subsequent
     * change to {@code originalUnitPrice}, discount, or the effective {@code unitPrice} (task 12.5's
     * {@code EstimateLinePackagePriceService}) reuse this same building block.
     *
     * <p>{@code row} MUST already be persisted (have a non-null id) before calling this method,
     * since the history row's {@code package_price_id} FK is {@code NOT NULL}.
     *
     * @param row the already-persisted per-package price row (new or just-changed) to snapshot into
     *            history
     * @return the persisted history row capturing {@code row}'s current state
     */
    @Transactional
    public EstimateLinePackagePriceHistoryEntity captureHistory(EstimateLinePackagePriceEntity row) {
        EstimateLinePackagePriceHistoryEntity history = new EstimateLinePackagePriceHistoryEntity();
        history.setPackagePrice(row);
        history.setOriginalUnitPrice(row.getOriginalUnitPrice());
        history.setDiscountKind(row.getDiscountKind());
        history.setDiscountValue(row.getDiscountValue());
        history.setUnitPrice(row.getUnitPrice());
        history.setChangedBy(resolveCurrentUser());
        history.setChangedAt(LocalDateTime.now());
        return historyDao.save(history);
    }

    /**
     * Returns every history row captured for {@code packagePriceId}, oldest-first by {@code id}, so
     * a per-package price's own change history is queryable (R6.1, R6.3).
     *
     * @param packagePriceId the owning {@code EstimateLinePackagePrice} id
     * @return every history row for {@code packagePriceId}, oldest-first; never {@code null}
     */
    @Transactional(readOnly = true)
    public List<EstimateLinePackagePriceHistoryEntity> findHistory(Long packagePriceId) {
        return historyDao.findByPackagePriceId(packagePriceId, Sort.by("id"));
    }

    /** Resolves the current authenticated caller's username, or {@code "SYSTEM"} when absent. */
    private String resolveCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            return auth.getName();
        }
        return "SYSTEM";
    }

    /** Returns the catalog {@code WorkPackagePrice} row matching {@code pkg}, or {@code null}. */
    private WorkPackagePriceEntity provenanceOf(List<WorkPackagePriceEntity> workPackagePrices,
                                                 OfferPackageEntity pkg) {
        for (WorkPackagePriceEntity price : workPackagePrices) {
            if (price != null && price.getOfferPackage() != null
                    && pkg.getId().equals(price.getOfferPackage().getId())) {
                return price;
            }
        }
        return null;
    }
}
