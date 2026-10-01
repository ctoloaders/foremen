package com.foremen.dao;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;

/**
 * DAO for {@link OfferEntity} (FOR-05-07, Requirements 1.1, 1.4), following the
 * {@code EstimateDao}/{@code RoomDao} finder convention: the generic {@link AdminDao} CRUD surface
 * plus derived finders backing the offer lifecycle in {@code OfferService}.
 *
 * <p>The {@link #findByProjectIdAndStatusNotIn(Long, List)} finder enforces the
 * one-non-terminal-offer-per-project invariant (R1.4) at the service layer before a
 * {@code prepareOffer} create — the partial unique index on {@code offers(project_id) WHERE status
 * NOT IN ('APPROVED','REJECTED','WITHDRAWN')} (changeset {@code 128}) is the ultimate backstop.
 */
@Repository
public interface OfferDao extends AdminDao<OfferEntity, Long> {

    /**
     * Every offer of a project whose status is NOT one of {@code excludedStatuses} — used with the
     * three terminal statuses ({@code APPROVED}/{@code REJECTED}/{@code WITHDRAWN}) to detect an
     * existing <b>active</b> (non-terminal) offer and reject a second {@code prepareOffer} with a
     * clean {@code 409 error.offer.active.exists} (R1.4) rather than letting the DB partial unique
     * index surface a raw constraint violation.
     *
     * @param projectId        the owning project id
     * @param excludedStatuses the statuses to exclude (the terminal statuses, to find only active offers)
     * @return the project's non-terminal offers (normally at most one)
     */
    List<OfferEntity> findByProjectIdAndStatusNotIn(Long projectId, List<OfferStatus> excludedStatuses);

    /**
     * The project's offer(s), regardless of status, ordered by id — a convenience finder for
     * lifecycle reads. Backs the agreed-version / downstream lookups.
     *
     * @param projectId the owning project id
     * @return every offer of the project (active and terminal), oldest first
     */
    List<OfferEntity> findByProjectIdOrderByIdAsc(Long projectId);
}
