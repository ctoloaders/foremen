package com.foremen.service.bank.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.data.jpa.domain.Specification;

import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.BankTransactionSuggestionEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.DetectionSource;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.SuggestionStatus;
import com.foremen.service.bank.ingest.IbanNormalizer;
import com.foremen.service.bank.ledger.LedgerFilter.DetectionFilter;
import com.foremen.service.bank.ledger.LedgerFilter.LedgerStatusFilter;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * FOR-07-04 (Requirement 20.3) — the JPA {@link Specification} of a {@link LedgerFilter}. Shared by the
 * page query, the totals aggregate and the "re-run detection on a filtered selection" id resolution, so
 * the three always agree.
 */
public final class LedgerSpecifications {

    /** {@code To_Review} statuses without a review condition (glossary). */
    static final Set<LinkStatus> TO_REVIEW_PLAIN = EnumSet.of(LinkStatus.UNLINKED, LinkStatus.SUGGESTED,
            LinkStatus.PARTIALLY_LINKED);

    private static final char ESCAPE = '\\';

    private LedgerSpecifications() {
    }

    /** The specification of {@code filter} ({@code null} → everything). */
    public static Specification<BankTransactionEntity> of(LedgerFilter filter) {
        LedgerFilter f = filter == null ? LedgerFilter.empty() : filter;
        return (root, query, cb) -> predicate(f, root, query, cb);
    }

    /** The filter predicate on a {@link BankTransactionEntity} root. */
    static Predicate predicate(LedgerFilter f, Root<BankTransactionEntity> root, CriteriaQuery<?> query,
                               CriteriaBuilder cb) {
        List<Predicate> ps = new ArrayList<>();
        if (f.transactionId() != null) {
            ps.add(cb.equal(root.get("id"), f.transactionId()));
        }
        Expression<LocalDate> effective = cb.coalesce(root.<LocalDate>get("bookingDate"),
                root.<LocalDate>get("operationDate"));
        if (f.from() != null) {
            ps.add(cb.greaterThanOrEqualTo(effective, f.from()));
        }
        if (f.to() != null) {
            ps.add(cb.lessThanOrEqualTo(effective, f.to()));
        }
        if (f.channel() != null) {
            ps.add(cb.equal(root.get("channel"), f.channel()));
        }
        if (f.direction() != null) {
            ps.add(f.direction() == LedgerFilter.LedgerDirection.INCOME
                    ? cb.greaterThan(root.get("amount"), BigDecimal.ZERO)
                    : cb.lessThan(root.get("amount"), BigDecimal.ZERO));
        }
        if (f.connectionId() != null) {
            ps.add(cb.equal(root.get("account").get("connection").get("id"), f.connectionId()));
        }
        if (f.accountId() != null) {
            ps.add(cb.equal(root.get("account").get("id"), f.accountId()));
        }
        if (!f.linkStatuses().isEmpty()) {
            ps.add(statusPredicate(f.linkStatuses(), root, cb));
        }
        if (f.kind() != null || f.projectId() != null) {
            Subquery<Long> sq = query.subquery(Long.class);
            Root<BankTransactionLinkEntity> l = sq.from(BankTransactionLinkEntity.class);
            List<Predicate> lp = new ArrayList<>();
            lp.add(cb.equal(l.get("transaction"), root));
            if (f.kind() != null) {
                lp.add(cb.equal(l.get("kind"), f.kind()));
            }
            if (f.projectId() != null) {
                lp.add(cb.equal(l.get("project").get("id"), f.projectId()));
            }
            sq.select(l.get("id")).where(lp.toArray(Predicate[]::new));
            ps.add(cb.exists(sq));
        }
        if (!f.detections().isEmpty()) {
            List<Predicate> any = new ArrayList<>();
            for (DetectionFilter d : EnumSet.copyOf(f.detections())) {
                any.add(detectionPredicate(d, root, query, cb));
            }
            ps.add(cb.or(any.toArray(Predicate[]::new)));
        }
        Expression<BigDecimal> abs = cb.abs(root.<BigDecimal>get("amount"));
        if (f.amountMin() != null) {
            ps.add(cb.greaterThanOrEqualTo(abs, f.amountMin().abs()));
        }
        if (f.amountMax() != null) {
            ps.add(cb.lessThanOrEqualTo(abs, f.amountMax().abs()));
        }
        if (f.q() != null && !f.q().isBlank()) {
            ps.add(textPredicate(f.q().strip(), root, cb));
        }
        if (Boolean.FALSE.equals(f.includePending())) {
            ps.add(cb.equal(root.get("status"), BankTxnStatus.POSTED));
        }
        return cb.and(ps.toArray(Predicate[]::new));
    }

    /** OR of the requested statuses; {@code TO_REVIEW} expands to its group. */
    static Predicate statusPredicate(List<LedgerStatusFilter> statuses, Root<BankTransactionEntity> root,
                                     CriteriaBuilder cb) {
        Set<LinkStatus> plain = EnumSet.noneOf(LinkStatus.class);
        boolean toReview = false;
        for (LedgerStatusFilter s : statuses) {
            if (s == LedgerStatusFilter.TO_REVIEW) {
                toReview = true;
            } else if (s != null) {
                plain.add(LinkStatus.valueOf(s.name()));
            }
        }
        List<Predicate> any = new ArrayList<>();
        if (!plain.isEmpty()) {
            any.add(root.get("linkStatus").in(plain));
        }
        if (toReview) {
            any.add(toReviewPredicate(root, cb));
        }
        return any.isEmpty() ? cb.conjunction() : cb.or(any.toArray(Predicate[]::new));
    }

    /** {@code UNLINKED ∪ SUGGESTED ∪ PARTIALLY_LINKED ∪ (AUTO_LINKED ∧ reviewed_at IS NULL)}. */
    static Predicate toReviewPredicate(Root<BankTransactionEntity> root, CriteriaBuilder cb) {
        return cb.or(root.get("linkStatus").in(TO_REVIEW_PLAIN),
                cb.and(cb.equal(root.get("linkStatus"), LinkStatus.AUTO_LINKED), cb.isNull(root.get("reviewedAt"))));
    }

    private static Predicate detectionPredicate(DetectionFilter d, Root<BankTransactionEntity> root,
                                                CriteriaQuery<?> query, CriteriaBuilder cb) {
        return switch (d) {
            case RULE -> cb.or(linkWithSource(DetectionSource.RULE, root, query, cb),
                    openSuggestionWithSource(DetectionSource.RULE, root, query, cb));
            case AI -> cb.or(linkWithSource(DetectionSource.AI, root, query, cb),
                    openSuggestionWithSource(DetectionSource.AI, root, query, cb));
            case MANUAL -> linkWithSource(DetectionSource.MANUAL, root, query, cb);
            case NONE -> cb.and(cb.not(linkWithSource(null, root, query, cb)),
                    cb.not(openSuggestionWithSource(null, root, query, cb)));
        };
    }

    /** EXISTS a link of the line (with the given source, any when {@code null}). */
    private static Predicate linkWithSource(DetectionSource source, Root<BankTransactionEntity> root,
                                            CriteriaQuery<?> query, CriteriaBuilder cb) {
        Subquery<Long> sq = query.subquery(Long.class);
        Root<BankTransactionLinkEntity> l = sq.from(BankTransactionLinkEntity.class);
        Predicate p = cb.equal(l.get("transaction"), root);
        if (source != null) {
            p = cb.and(p, cb.equal(l.get("source"), source));
        }
        sq.select(l.get("id")).where(p);
        return cb.exists(sq);
    }

    /** EXISTS an {@code OPEN} suggestion of the line (with the given source, any when {@code null}). */
    private static Predicate openSuggestionWithSource(DetectionSource source, Root<BankTransactionEntity> root,
                                                      CriteriaQuery<?> query, CriteriaBuilder cb) {
        Subquery<Long> sq = query.subquery(Long.class);
        Root<BankTransactionSuggestionEntity> s = sq.from(BankTransactionSuggestionEntity.class);
        Predicate p = cb.and(cb.equal(s.get("transaction"), root), cb.equal(s.get("status"), SuggestionStatus.OPEN));
        if (source != null) {
            p = cb.and(p, cb.equal(s.get("source"), source));
        }
        sq.select(s.get("id")).where(p);
        return cb.exists(sq);
    }

    /** Case-insensitive contains over title and counterparty name; IBAN by its normalized form. */
    private static Predicate textPredicate(String q, Root<BankTransactionEntity> root, CriteriaBuilder cb) {
        String pattern = "%" + escape(q.toLowerCase(Locale.ROOT)) + "%";
        List<Predicate> any = new ArrayList<>();
        any.add(cb.like(cb.lower(root.get("title")), pattern, ESCAPE));
        any.add(cb.like(cb.lower(root.get("counterpartyName")), pattern, ESCAPE));
        String iban = ibanFragment(q);
        if (iban != null) {
            any.add(cb.like(root.get("counterpartyIban"), "%" + escape(iban) + "%", ESCAPE));
        }
        return cb.or(any.toArray(Predicate[]::new));
    }

    /** The query as an IBAN fragment (spaces removed, upper case), or {@code null} when it cannot be one. */
    static String ibanFragment(String q) {
        String compact = q.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (compact.length() < 4 || !compact.matches("[A-Z0-9]+")) {
            return null;
        }
        return IbanNormalizer.normalize(compact).orElse(compact);
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
