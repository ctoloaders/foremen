package com.foremen.service.bank.sync;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.BankSyncRunAccountDao;
import com.foremen.dao.BankSyncRunDao;
import com.foremen.dao.model.BankSyncRunEntity;
import com.foremen.dao.model.SyncRunStatus;
import com.foremen.exception.ForemenApiException;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;

/**
 * FOR-07-04 (Requirements 5.1, 19.6) — read-only access to the sync history: a filtered, pageable
 * run list and a run detail with the per-account breakdown. Mapping happens inside the read
 * transaction, so lazy associations never leak into the web layer.
 */
@Service
@RequiredArgsConstructor
public class BankSyncRunQueryService {

    public static final String NOT_FOUND = "error.entity.not.found";
    /** {@code from > to} on the sync history filter. */
    public static final String PERIOD_INVALID = "error.bank.syncRun.periodInvalid";

    private final BankSyncRunDao runDao;
    private final BankSyncRunAccountDao runAccountDao;

    /**
     * Runs matching the filters; every filter is optional. The period applies to the run's creation
     * (queue) time, inclusive on both ends.
     */
    @Transactional(readOnly = true)
    public Page<BankSyncRunDto> list(Long connectionId, Collection<SyncRunStatus> statuses,
                                     LocalDate from, LocalDate to, Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, PERIOD_INVALID);
        }
        return runDao.findAll(filter(connectionId, statuses, from, to), pageable)
                .map(r -> BankSyncRunDto.of(r, null));
    }

    /** One run with its per-account breakdown. */
    @Transactional(readOnly = true)
    public BankSyncRunDto get(Long id) {
        BankSyncRunEntity run = (id == null ? java.util.Optional.<BankSyncRunEntity>empty() : runDao.findById(id))
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, new Object[]{id}));
        List<BankSyncRunDto.AccountBreakdown> accounts = runAccountDao.findByRunIdOrderByIdAsc(run.getId()).stream()
                .map(BankSyncRunDto.AccountBreakdown::of)
                .toList();
        return BankSyncRunDto.of(run, accounts);
    }

    static Specification<BankSyncRunEntity> filter(Long connectionId, Collection<SyncRunStatus> statuses,
                                                   LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (connectionId != null) {
                predicates.add(cb.equal(root.get("connection").get("id"), connectionId));
            }
            if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdDate"), from.atStartOfDay()));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("createdDate"), to.plusDays(1).atStartOfDay()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
