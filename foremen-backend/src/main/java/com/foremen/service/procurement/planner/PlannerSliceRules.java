package com.foremen.service.procurement.planner;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchasePositionVariantEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.PositionDelivery;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * FOR-11-01 (R2): the single definition of "late", "urgent" and the planner slices.
 *
 * <p>The pure functions work on a {@link PositionSnapshot}; the {@code *Predicate} methods build the
 * same definitions as JPA predicates over a {@link PurchasePositionEntity} root. Both sides use the
 * stage sets below, so the row flags and the SQL filters cannot drift (see the SQL equivalence test).
 * "Today" is the current date in {@code Europe/Warsaw}, taken from the injected {@link Clock}.
 */
@Component
public class PlannerSliceRules {

    public static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    /** Stages before the order is placed: {@code PLANNED..WITH_BUYER}. */
    public static final Set<PositionStatus> BEFORE_ORDERED = Set.copyOf(EnumSet.of(
            PositionStatus.PLANNED, PositionStatus.PROPOSED, PositionStatus.ACCEPTED,
            PositionStatus.IN_PROFORMA, PositionStatus.CLIENT_PAID, PositionStatus.WITH_BUYER));


    /** {@code CLIENT_PAID} and every later non-cancelled stage. */
    public static final Set<PositionStatus> PAID_FROM = Set.copyOf(EnumSet.of(
            PositionStatus.CLIENT_PAID, PositionStatus.WITH_BUYER, PositionStatus.ORDERED,
            PositionStatus.SUPPLIER_PAID, PositionStatus.IN_DELIVERY,
            PositionStatus.PARTIALLY_RECEIVED, PositionStatus.RECEIVED));

    /** {@code AGREED} stages before the client has paid, excluding {@code CANCELLED}. */
    public static final Set<PositionStatus> BEFORE_PAID = Set.copyOf(EnumSet.of(
            PositionStatus.PLANNED, PositionStatus.PROPOSED, PositionStatus.ACCEPTED,
            PositionStatus.IN_PROFORMA));

    /** Planner slices (R2.2). {@code ALL} applies no restriction. */
    public enum Slice { ALL, ON_SCHEDULE, LATE, CHANGED, PAID, NOT_PAID }

    /** The inputs of the rules; {@code variantCount} is the number of recorded variants. */
    public record PositionSnapshot(SourcingPath path, PositionStatus status, LocalDate needByDate,
                                   LocalDate orderByDate, LocalDate plannedDeliveryDate,
                                   long variantCount, boolean clientUnpaid) {
    }

    private final Clock clock;

    public PlannerSliceRules(Clock clock) {
        this.clock = clock;
    }

    /** Today in {@code Europe/Warsaw}. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(WARSAW));
    }

    // ---------------------------------------------------------------- pure side

    /** Needs no more attention: {@code Delivery_Complete} (FOR-11-06 R10.4) or {@code CANCELLED}. */
    public static boolean isTerminal(PositionSnapshot s) {
        return s.status() == PositionStatus.CANCELLED
                || PositionDelivery.isComplete(s.status(), s.clientUnpaid());
    }

    /** {@code COALESCE(plannedDeliveryDate, needByDate)}. */
    public static LocalDate siteDeliveryDate(PositionSnapshot s) {
        return s.plannedDeliveryDate() != null ? s.plannedDeliveryDate() : s.needByDate();
    }

    public static boolean isLate(PositionSnapshot s, LocalDate today) {
        if (isTerminal(s)) {
            return false;
        }
        LocalDate site = siteDeliveryDate(s);
        if (site != null && site.isBefore(today)) {
            return true;
        }
        return s.orderByDate() != null && s.orderByDate().isBefore(today)
                && BEFORE_ORDERED.contains(s.status());
    }

    public static boolean isUrgent(PositionSnapshot s, LocalDate today, int urgentDays) {
        if (isTerminal(s) || !BEFORE_ORDERED.contains(s.status())) {
            return false;
        }
        LocalDate site = siteDeliveryDate(s);
        boolean soon = site != null && !site.isAfter(today.plusDays(urgentDays));
        return soon || isLate(s, today);
    }

    /** Whether the position belongs to the given slice. */
    public static boolean inSlice(Slice slice, PositionSnapshot s, LocalDate today) {
        return switch (slice) {
            case ALL -> true;
            case ON_SCHEDULE -> !isTerminal(s) && !isLate(s, today);
            case LATE -> isLate(s, today);
            case CHANGED -> s.variantCount() > 0;
            case PAID -> s.path() == SourcingPath.AGREED && PAID_FROM.contains(s.status())
                    && !(s.status() == PositionStatus.RECEIVED && s.clientUnpaid());
            case NOT_PAID -> s.path() == SourcingPath.AGREED && (BEFORE_PAID.contains(s.status())
                    || (s.status() == PositionStatus.RECEIVED && s.clientUnpaid()));
        };
    }

    /** All slices (except {@code ALL}) the position belongs to. */
    public static Set<Slice> slice(PositionSnapshot s, LocalDate today) {
        EnumSet<Slice> result = EnumSet.noneOf(Slice.class);
        for (Slice slice : Slice.values()) {
            if (slice != Slice.ALL && inSlice(slice, s, today)) {
                result.add(slice);
            }
        }
        return result;
    }

    // ------------------------------------------------------------ predicate side

    public static Predicate latePredicate(Root<PurchasePositionEntity> root, CriteriaBuilder cb,
                                          LocalDate today) {
        Predicate nonTerminal = cb.not(terminal(root, cb));
        Expression<LocalDate> site = siteExpression(root, cb);
        // isNotNull guards keep the predicate two-valued so NOT(...) in ON_SCHEDULE never yields UNKNOWN
        Predicate siteLate = cb.and(cb.isNotNull(site), cb.lessThan(site, today));
        Predicate orderLate = cb.and(
                cb.isNotNull(root.<LocalDate>get("orderByDate")),
                cb.lessThan(root.<LocalDate>get("orderByDate"), today),
                root.get("status").in(BEFORE_ORDERED));
        return cb.and(nonTerminal, cb.or(siteLate, orderLate));
    }

    public static Predicate urgentPredicate(Root<PurchasePositionEntity> root, CriteriaBuilder cb,
                                            LocalDate today, int urgentDays) {
        Predicate beforeOrdered = root.get("status").in(BEFORE_ORDERED);
        Expression<LocalDate> site = siteExpression(root, cb);
        Predicate soon = cb.and(cb.isNotNull(site),
                cb.lessThanOrEqualTo(site, today.plusDays(urgentDays)));
        return cb.and(beforeOrdered, cb.or(soon, latePredicate(root, cb, today)));
    }

    public static Predicate slicePredicate(Slice slice, Root<PurchasePositionEntity> root,
                                           CriteriaQuery<?> query, CriteriaBuilder cb, LocalDate today) {
        return switch (slice) {
            case ALL -> cb.conjunction();
            case ON_SCHEDULE -> cb.and(cb.not(terminal(root, cb)),
                    cb.not(latePredicate(root, cb, today)));
            case LATE -> latePredicate(root, cb, today);
            case CHANGED -> hasVariants(root, query, cb);
            case PAID -> cb.and(agreed(root, cb), root.get("status").in(PAID_FROM),
                    cb.not(cb.and(cb.equal(root.get("status"), PositionStatus.RECEIVED),
                            cb.isTrue(root.<Boolean>get("clientUnpaid")))));
            case NOT_PAID -> cb.and(agreed(root, cb), cb.or(root.get("status").in(BEFORE_PAID),
                    cb.and(cb.equal(root.get("status"), PositionStatus.RECEIVED),
                            cb.isTrue(root.<Boolean>get("clientUnpaid")))));
        };
    }

    private static Predicate terminal(Root<PurchasePositionEntity> root, CriteriaBuilder cb) {
        return cb.or(cb.equal(root.get("status"), PositionStatus.CANCELLED),
                PositionDelivery.completePredicate(root, cb));
    }

    private static Predicate agreed(Root<PurchasePositionEntity> root, CriteriaBuilder cb) {
        return cb.equal(root.get("sourcingPath"), SourcingPath.AGREED);
    }

    private static Expression<LocalDate> siteExpression(Root<PurchasePositionEntity> root,
                                                        CriteriaBuilder cb) {
        return cb.coalesce(root.<LocalDate>get("plannedDeliveryDate"), root.<LocalDate>get("needByDate"));
    }

    private static Predicate hasVariants(Root<PurchasePositionEntity> root, CriteriaQuery<?> query,
                                         CriteriaBuilder cb) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<PurchasePositionVariantEntity> v = sub.from(PurchasePositionVariantEntity.class);
        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(v.get("position"), root));
        sub.select(v.get("id")).where(where.toArray(new Predicate[0]));
        return cb.exists(sub);
    }
}
