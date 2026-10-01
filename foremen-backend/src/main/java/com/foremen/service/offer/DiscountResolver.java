package com.foremen.service.offer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.DiscountScope;

/**
 * Resolves the single <b>surviving</b> discount per estimate line for an offer, applying the
 * deterministic <b>override-and-cancel</b> scope hierarchy (FOR-05-07, Requirements 2.5, 4.9,
 * 10.16).
 *
 * <p>For any line the surviving discount is the one at the highest scope covering that line:
 * a {@link DiscountScope#GLOBAL} discount supersedes the {@link DiscountScope#CATEGORY} and
 * {@link DiscountScope#LINE} discounts it covers, and a {@code CATEGORY} discount supersedes the
 * {@code LINE} discounts within its work-type group. Only that surviving discount is applied to the
 * line, and its effective money amount is clamped so it never exceeds the line's net base.
 *
 * <p>The resolver is a pure, total, deterministic function of its inputs: it performs no I/O, holds
 * no state, and — critically — is <b>independent of the order</b> in which discounts are supplied
 * (Requirements 2.5 / 4.9 / 10.16). It mirrors the repo-wide stateless {@code @Component}
 * convention (e.g. {@code DiscountCalculator}, {@code PackageZlM2Resolver}) so it is exercised
 * directly by property-based tests without persistence. When more than one discount exists at the
 * same surviving scope for a line (e.g. two overlapping {@code CATEGORY} discounts), the resolver
 * picks deterministically by {@code (targetId, kind, value)} so the result is stable regardless of
 * insertion order.
 */
@Component
public class DiscountResolver {

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * A discount to resolve, as a pure value (decoupled from the {@code OfferDiscount} entity so the
     * core is directly property-testable).
     *
     * @param scope    the scope at which the discount applies ({@code GLOBAL}/{@code CATEGORY}/{@code LINE})
     * @param targetId {@code null} for {@code GLOBAL}; the work-category id for {@code CATEGORY}; the
     *                 estimate-line id for {@code LINE}
     * @param kind     {@code PERCENT} or {@code ABSOLUTE}
     * @param value    the discount magnitude (a percentage for {@code PERCENT}, a money amount for
     *                 {@code ABSOLUTE}); treated as zero when {@code null}
     */
    public record DiscountInput(DiscountScope scope, Long targetId, DiscountKind kind, BigDecimal value) {
    }

    /**
     * One estimate line the discounts apply to, as a pure value.
     *
     * @param lineId     the estimate-line id
     * @param categoryId the work-category (work-type group) id this line belongs to; {@code null}
     *                   when the line has no category (then only {@code GLOBAL} and its own
     *                   {@code LINE} discount can cover it)
     * @param netBase    the line's net base amount discounts are measured against; treated as zero
     *                   when {@code null} or negative
     */
    public record EstimateLineInput(Long lineId, Long categoryId, BigDecimal netBase) {
    }

    /**
     * The single surviving discount resolved for a line: the discount that won the override-and-cancel
     * hierarchy plus its clamped effective money amount.
     *
     * @param scope   the scope of the surviving discount
     * @param kind    the kind of the surviving discount
     * @param value   the raw configured value of the surviving discount
     * @param netBase the line's net base the discount was applied to
     * @param amount  the effective money amount removed from the line's net base, rounded to 2
     *                decimals and clamped to {@code [0, netBase]}
     */
    public record EffectiveDiscount(DiscountScope scope, DiscountKind kind, BigDecimal value,
                                    BigDecimal netBase, BigDecimal amount) {
    }

    /**
     * Resolves the single surviving discount for each estimate line and its clamped effective money
     * amount, applying the {@code GLOBAL} &gt; {@code CATEGORY} &gt; {@code LINE} override-and-cancel
     * hierarchy. Lines with no covering discount are absent from the result map.
     *
     * <p>The result is independent of the order of {@code discounts} (Requirements 2.5 / 4.9 /
     * 10.16): the surviving discount at a scope is chosen deterministically by {@code (targetId,
     * kind, value)} when several exist at that scope.
     *
     * @param discounts     the offer's discounts (applied discounts and accepted propositions); a
     *                      {@code null} or empty collection yields an empty map
     * @param estimateLines the lines to resolve against; a {@code null} or empty collection yields an
     *                      empty map
     * @return an insertion-ordered map from line id to its {@link EffectiveDiscount}; a line with no
     *         surviving discount has no entry
     */
    public Map<Long, EffectiveDiscount> resolveEffective(List<DiscountInput> discounts,
                                                         List<EstimateLineInput> estimateLines) {
        Map<Long, EffectiveDiscount> result = new LinkedHashMap<>();
        if (estimateLines == null || estimateLines.isEmpty()) {
            return result;
        }

        Buckets buckets = bucketize(discounts);

        for (EstimateLineInput line : estimateLines) {
            if (line == null || line.lineId() == null) {
                continue;
            }
            DiscountInput surviving = buckets.survivingFor(line);
            if (surviving == null) {
                continue;
            }
            BigDecimal netBase = normalizeBase(line.netBase());
            BigDecimal amount = effectiveAmount(surviving, netBase);
            result.put(line.lineId(),
                    new EffectiveDiscount(surviving.scope(), surviving.kind(), surviving.value(), netBase, amount));
        }

        return result;
    }

    /**
     * Collapses the discount list into a single deterministic winner per bucket (the {@code GLOBAL}
     * discount, one per category, one per line) so the resolution never depends on the order of
     * {@code discounts}. Ties within a bucket are broken by {@link #CANDIDATE_ORDER}.
     */
    private Buckets bucketize(List<DiscountInput> discounts) {
        DiscountInput global = null;
        Map<Long, DiscountInput> byCategory = new HashMap<>();
        Map<Long, DiscountInput> byLine = new HashMap<>();

        if (discounts != null) {
            for (DiscountInput d : discounts) {
                if (d == null || d.scope() == null) {
                    continue;
                }
                switch (d.scope()) {
                    case GLOBAL -> global = pickDeterministic(global, d);
                    case CATEGORY -> mergeTargeted(byCategory, d);
                    case LINE -> mergeTargeted(byLine, d);
                }
            }
        }
        return new Buckets(global, byCategory, byLine);
    }

    private void mergeTargeted(Map<Long, DiscountInput> bucket, DiscountInput d) {
        if (d.targetId() != null) {
            bucket.merge(d.targetId(), d, this::pickDeterministic);
        }
    }

    /**
     * The per-bucket surviving discounts, with the {@code GLOBAL} &gt; {@code CATEGORY} &gt;
     * {@code LINE} override-and-cancel selection applied per line.
     */
    private record Buckets(DiscountInput global, Map<Long, DiscountInput> byCategory,
                           Map<Long, DiscountInput> byLine) {

        DiscountInput survivingFor(EstimateLineInput line) {
            if (global != null) {
                return global;
            }
            if (line.categoryId() != null) {
                DiscountInput category = byCategory.get(line.categoryId());
                if (category != null) {
                    return category;
                }
            }
            return byLine.get(line.lineId());
        }
    }

    /**
     * Deterministic tie-breaker between two candidate discounts at the same scope/bucket, so the
     * surviving discount is stable regardless of insertion order. Orders by {@code targetId}, then
     * {@code kind}, then {@code value}; a non-null candidate always beats {@code null}.
     */
    private DiscountInput pickDeterministic(DiscountInput a, DiscountInput b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return CANDIDATE_ORDER.compare(a, b) <= 0 ? a : b;
    }

    private static final Comparator<DiscountInput> CANDIDATE_ORDER = Comparator
            .comparing(DiscountInput::targetId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(d -> d.kind() == null ? null : d.kind().name(),
                    Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(d -> d.value() == null ? BigDecimal.ZERO : d.value(),
                    Comparator.nullsFirst(Comparator.naturalOrder()));

    /**
     * The effective money amount a discount removes from a net base, rounded to 2 decimals and
     * clamped to {@code [0, netBase]}: a {@code PERCENT} discount removes {@code netBase ×
     * value/100}; an {@code ABSOLUTE} discount removes {@code value}. A {@code null} kind or value
     * removes nothing.
     */
    private BigDecimal effectiveAmount(DiscountInput discount, BigDecimal netBase) {
        if (discount.kind() == null || discount.value() == null || discount.value().signum() <= 0
                || netBase.signum() <= 0) {
            return round2(BigDecimal.ZERO);
        }

        BigDecimal raw;
        if (discount.kind() == DiscountKind.PERCENT) {
            raw = netBase.multiply(discount.value()).divide(HUNDRED, 10, RoundingMode.HALF_UP);
        } else {
            raw = discount.value();
        }

        if (raw.signum() < 0) {
            raw = BigDecimal.ZERO;
        }
        if (raw.compareTo(netBase) > 0) {
            raw = netBase;
        }
        return round2(raw);
    }

    private BigDecimal normalizeBase(BigDecimal netBase) {
        if (netBase == null || netBase.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return netBase;
    }

    private BigDecimal round2(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
