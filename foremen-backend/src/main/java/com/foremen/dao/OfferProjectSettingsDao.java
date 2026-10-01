package com.foremen.dao;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.OfferProjectSettingsEntity;

/**
 * DAO for {@link OfferProjectSettingsEntity} (FOR-05-07, Requirement 6.4): the per-project
 * escalation-threshold override row consulted by {@code NegotiationService} when gating a
 * {@code MANAGER_PROPOSAL} through {@code EscalationPolicy}.
 *
 * <p>At most one settings row exists per project (the {@code project_id} FK is UNIQUE), so
 * {@link #findByProjectId(Long)} returns an {@link Optional}. A missing row means "no per-project
 * override" — {@code EscalationPolicy} then falls back to the GLOBAL config default (R6.4).
 */
@Repository
public interface OfferProjectSettingsDao extends AdminDao<OfferProjectSettingsEntity, Long> {

    /**
     * The per-project escalation override for {@code projectId}, or empty when the project has none
     * (fall back to the GLOBAL config default).
     *
     * @param projectId the owning project id
     * @return the project's escalation override, if any
     */
    Optional<OfferProjectSettingsEntity> findByProjectId(Long projectId);
}
