package com.foremen.service.clientpayment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ClientPaymentDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ClientPaymentEntity;
import com.foremen.service.procurement.access.ProcurementProjectAccess;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * FOR-11-06 (R1.4, R1.5, R1.7, R2.2, R3.1, R3.4): the combined, paginated list and the filtered totals of
 * the client payments.
 *
 * <p>One {@link Specification} drives both {@link #find} and {@link #summary}: filter, project scope
 * (BUYER and FINANCIER widened through {@link ProcurementProjectAccess}, ADMIN unrestricted) and the per-role
 * kind rule ({@link ClientPaymentVisibility}). The default order is {@code dueDate ASC NULLS LAST, id ASC}.
 * A project outside the caller's scope yields an empty result (the by-id 404 of R3.4 belongs to the read
 * service).
 */
@Service
public class ClientPaymentQueryService {

    private final ClientPaymentDao dao;
    private final UserDao userDao;
    private final ProcurementProjectAccess projectAccess;
    private final EntityManager entityManager;
    private final Clock clock;

    public ClientPaymentQueryService(ClientPaymentDao dao, UserDao userDao, ProcurementProjectAccess projectAccess,
                                     EntityManager entityManager, Clock clock) {
        this.dao = dao;
        this.userDao = userDao;
        this.projectAccess = projectAccess;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** The default list order: {@code dueDate ASC NULLS LAST, id ASC}. */
    public static Sort defaultSort() {
        return Sort.by(Sort.Order.asc("dueDate").nullsLast(), Sort.Order.asc("id"));
    }

    /** Keeps the caller's sort, else the default; always appends {@code id ASC} as the stable tie-breaker. */
    static Pageable withStableSort(Pageable pageable) {
        Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : defaultSort();
        if (sort.getOrderFor("id") == null) {
            sort = sort.and(Sort.by(Sort.Order.asc("id")));
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    @Transactional(readOnly = true)
    public Page<ClientPaymentEntity> find(Pageable pageable, ClientPaymentFilter filter) {
        return dao.findAll(specification(filter == null ? ClientPaymentFilter.none() : filter),
                withStableSort(pageable));
    }

    @Transactional(readOnly = true)
    public ClientPaymentSummary summary(ClientPaymentFilter filter) {
        Specification<ClientPaymentEntity> spec = specification(filter == null ? ClientPaymentFilter.none() : filter);
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<ClientPaymentEntity> root = cq.from(ClientPaymentEntity.class);
        Predicate where = spec.toPredicate(root, cq, cb);
        cq.multiselect(cb.countDistinct(root), cb.sum(root.<BigDecimal>get("amountGross")),
                cb.sum(root.<BigDecimal>get("paidGross")));
        if (where != null) {
            cq.where(where);
        }
        Object[] row = entityManager.createQuery(cq).getSingleResult();
        long count = row[0] == null ? 0L : ((Number) row[0]).longValue();
        BigDecimal amount = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
        BigDecimal paid = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];
        return new ClientPaymentSummary(count, amount, paid, amount.subtract(paid));
    }

    /** The specification of the current caller; shared by the page and the summary. */
    Specification<ClientPaymentEntity> specification(ClientPaymentFilter f) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = userId(auth);
        String role = userId == null ? null : userDao.findRoleCodeById(userId).orElse(null);
        // ADMIN bypasses the project scope (as ProjectScopedService.addRequiredQuery does).
        Set<Long> allowed = "ADMIN".equals(role) ? null : projectAccess.allowedProjectIds(userId);
        return build(f, ClientPaymentVisibility.forRole(role), allowed, LocalDate.now(clock));
    }

    static Specification<ClientPaymentEntity> build(ClientPaymentFilter f, ClientPaymentVisibility visibility,
                                                    Set<Long> allowedProjectIds, LocalDate today) {
        List<Specification<ClientPaymentEntity>> parts = List.of(
                ClientPaymentSpecifications.projectScope(allowedProjectIds, f.projectId()),
                ClientPaymentSpecifications.visibility(visibility),
                ClientPaymentSpecifications.kind(f.kind()),
                ClientPaymentSpecifications.displayStatus(f.status(), today),
                ClientPaymentSpecifications.overdue(f.overdue(), today),
                ClientPaymentSpecifications.dueRange(f.dueFrom(), f.dueTo()),
                ClientPaymentSpecifications.text(f.query()));
        Specification<ClientPaymentEntity> all = (root, q, cb) -> cb.conjunction();
        for (Specification<ClientPaymentEntity> p : parts) {
            all = all.and(p);
        }
        return all;
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
