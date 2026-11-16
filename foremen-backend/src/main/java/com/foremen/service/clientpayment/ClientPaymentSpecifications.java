package com.foremen.service.clientpayment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.data.jpa.domain.Specification;

import com.foremen.dao.model.ClientPaymentEntity;
import com.foremen.service.clientpayment.ClientPaymentStatusMapper.StoredMatch;
import com.foremen.service.payment.ScheduleStatus;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * FOR-11-06 (R1.5, R1.7, R3.1): the building blocks of the client-payment list specification. Each method
 * returns an independent {@link Specification}; {@link ClientPaymentQueryService} ANDs them.
 */
public final class ClientPaymentSpecifications {

    private static final String PAID = "PAID";
    private static final String CANCELLED = "CANCELLED";
    private static final String OVERDUE = "OVERDUE";

    private ClientPaymentSpecifications() {
    }

    /** Project scope: {@code null} allowed set means unrestricted (ADMIN); an empty set matches nothing. */
    public static Specification<ClientPaymentEntity> projectScope(Set<Long> allowedProjectIds, Long projectId) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (allowedProjectIds != null) {
                if (allowedProjectIds.isEmpty()) {
                    return cb.disjunction();
                }
                ps.add(root.get("project").get("id").in(allowedProjectIds));
            }
            if (projectId != null) {
                ps.add(cb.equal(root.get("project").get("id"), projectId));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /** The per-role kind rule (R1.7, R3.1). */
    public static Specification<ClientPaymentEntity> visibility(ClientPaymentVisibility visibility) {
        return (root, q, cb) -> switch (visibility) {
            case ALL -> cb.conjunction();
            case WORKS_ONLY -> cb.equal(root.get("kind"), ClientPaymentKind.WORKS);
            case MATERIALS_ONLY -> cb.equal(root.get("kind"), ClientPaymentKind.MATERIALS);
            case NONE -> cb.disjunction();
            case CLIENT -> {
                Join<Object, Object> tranche = root.join("tranche", JoinType.LEFT);
                Join<Object, Object> schedule = tranche.join("schedule", JoinType.LEFT);
                Predicate works = cb.and(cb.equal(root.get("kind"), ClientPaymentKind.WORKS),
                        cb.equal(schedule.get("status"), ScheduleStatus.PUBLISHED));
                Predicate materials = cb.and(cb.equal(root.get("kind"), ClientPaymentKind.MATERIALS),
                        root.get("status").in(ClientPaymentVisibility.CLIENT_MATERIALS_STATUSES));
                yield cb.or(works, materials);
            }
        };
    }

    public static Specification<ClientPaymentEntity> kind(ClientPaymentKind kind) {
        return (root, q, cb) -> kind == null ? cb.conjunction() : cb.equal(root.get("kind"), kind);
    }

    /** Display status translated to stored values per kind through the mapper's inverse. */
    public static Specification<ClientPaymentEntity> displayStatus(DisplayStatus display, LocalDate today) {
        return (root, q, cb) -> {
            if (display == null) {
                return cb.conjunction();
            }
            List<Predicate> perKind = new ArrayList<>();
            for (ClientPaymentKind kind : ClientPaymentKind.values()) {
                List<Predicate> matches = new ArrayList<>();
                for (StoredMatch m : ClientPaymentStatusMapper.fromDisplay(kind, display)) {
                    Predicate stored = cb.equal(root.get("status"), m.storedStatus());
                    matches.add(switch (m.dueRule()) {
                        case ANY -> stored;
                        case PAST_DUE -> cb.and(stored, pastDue(root, cb, today));
                        case NOT_PAST_DUE -> cb.and(stored, cb.not(pastDue(root, cb, today)));
                    });
                }
                if (!matches.isEmpty()) {
                    perKind.add(cb.and(cb.equal(root.get("kind"), kind), cb.or(matches.toArray(new Predicate[0]))));
                }
            }
            return perKind.isEmpty() ? cb.disjunction() : cb.or(perKind.toArray(new Predicate[0]));
        };
    }

    /** Mirrors {@link ClientPaymentStatusMapper#overdue}: not paid / cancelled and (stored works OVERDUE or past due). */
    public static Specification<ClientPaymentEntity> overdue(Boolean overdue, LocalDate today) {
        return (root, q, cb) -> {
            if (overdue == null) {
                return cb.conjunction();
            }
            Predicate closed = root.get("status").in(PAID, CANCELLED);
            Predicate storedOverdue = cb.and(cb.equal(root.get("kind"), ClientPaymentKind.WORKS),
                    cb.equal(root.get("status"), OVERDUE));
            Predicate isOverdue = cb.and(cb.not(closed), cb.or(storedOverdue, pastDue(root, cb, today)));
            return overdue ? isOverdue : cb.not(isOverdue);
        };
    }

    public static Specification<ClientPaymentEntity> dueRange(LocalDate from, LocalDate to) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("dueDate"), from));
            }
            if (to != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("dueDate"), to));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /** Case-insensitive match on the proforma number or the note; LIKE wildcards in the input are escaped. */
    public static Specification<ClientPaymentEntity> text(String query) {
        return (root, q, cb) -> {
            if (query == null || query.isBlank()) {
                return cb.conjunction();
            }
            String pattern = "%" + escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%";
            Join<Object, Object> proforma = root.join("proforma", JoinType.LEFT);
            return cb.or(
                    cb.like(cb.lower(proforma.get("number")), pattern, '\\'),
                    cb.like(cb.lower(root.get("note")), pattern, '\\'));
        };
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** {@code dueDate < today}; false (not null) for a missing due date. */
    private static Predicate pastDue(Root<ClientPaymentEntity> root, CriteriaBuilder cb, LocalDate today) {
        return cb.and(cb.isNotNull(root.get("dueDate")), cb.lessThan(root.get("dueDate"), today));
    }
}
