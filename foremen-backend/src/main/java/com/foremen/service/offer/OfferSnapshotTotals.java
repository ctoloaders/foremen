package com.foremen.service.offer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.foremen.dao.model.OfferDiscountEntity;
import com.foremen.service.estimate.EstimatePackagePricingService;
import com.foremen.service.offer.DiscountResolver.DiscountInput;
import com.foremen.service.offer.DiscountResolver.EffectiveDiscount;
import com.foremen.service.offer.DiscountResolver.EstimateLineInput;
import com.foremen.service.offer.OfferTotalsCalculator.OfferTotals;

/**
 * FOR-05-07b D7: computes an offer's totals from its frozen {@link OfferSnapshot} (effective unit price
 * times quantity per row), the offer discounts and the snapshot VAT rate, so the totals never depend on the
 * live estimate. Pure and static; the discount resolution and the totals folding are the same
 * {@link DiscountResolver} and {@link OfferTotalsCalculator} the live path uses.
 *
 * <p>Rows keep the estimate line id (version 2 rows written after D7), so a LINE discount resolves; a row
 * without one gets a synthetic negative key and is reachable by GLOBAL and CATEGORY discounts only. With a
 * package applied, the base is the stored package subtotal plus the rows not covered by the package.
 */
public final class OfferSnapshotTotals {

    private OfferSnapshotTotals() {
    }

    /**
     * Whether the offer totals can be computed from the snapshot: a version 2 snapshot, and, when a package
     * is applied, one that recorded its subtotal (older package snapshots keep the live path).
     */
    public static boolean usable(OfferSnapshot snapshot) {
        if (snapshot == null || snapshot.version() < 2) {
            return false;
        }
        boolean hasPackage = snapshot.appliedPackageCode() != null && !snapshot.appliedPackageCode().isBlank();
        return !hasPackage || snapshot.packageSubtotal() != null;
    }

    public static OfferTotals compute(OfferSnapshot snapshot, List<OfferDiscountEntity> discounts,
                                      DiscountResolver resolver, OfferTotalsCalculator calculator) {
        Map<Long, BigDecimal> prices = new HashMap<>();
        List<EstimateLineInput> lines = new ArrayList<>();
        long synthetic = -2L; // -1 is the package subtotal key
        if (snapshot.groups() != null) {
            for (OfferSnapshot.Group group : snapshot.groups()) {
                if (group == null || group.rows() == null) {
                    continue;
                }
                for (OfferSnapshot.Row row : group.rows()) {
                    if (row == null) {
                        continue;
                    }
                    Long key = row.lineId() != null ? row.lineId() : synthetic--;
                    BigDecimal net = rowNet(row);
                    lines.add(new EstimateLineInput(key, group.categoryId(), net));
                    if (!Boolean.TRUE.equals(row.packageCovered())) {
                        prices.put(key, net);
                    }
                }
            }
        }
        if (snapshot.packageSubtotal() != null) {
            prices.put(EstimatePackagePricingService.PACKAGE_PRICE_KEY, snapshot.packageSubtotal());
        }
        Map<Long, EffectiveDiscount> effective = resolver.resolveEffective(toInputs(discounts), lines);
        return calculator.compute(prices, effective, snapshot.vatRatePct());
    }

    private static BigDecimal rowNet(OfferSnapshot.Row row) {
        if (row.unitPrice() == null || row.quantity() == null) {
            return row.valueNet() != null ? row.valueNet() : BigDecimal.ZERO;
        }
        return row.unitPrice().multiply(row.quantity()).setScale(2, RoundingMode.HALF_UP);
    }

    private static List<DiscountInput> toInputs(List<OfferDiscountEntity> discounts) {
        List<DiscountInput> inputs = new ArrayList<>();
        if (discounts != null) {
            for (OfferDiscountEntity d : discounts) {
                if (d != null) {
                    inputs.add(new DiscountInput(d.getScope(), d.getTargetId(), d.getKind(), d.getValue()));
                }
            }
        }
        return inputs;
    }
}
