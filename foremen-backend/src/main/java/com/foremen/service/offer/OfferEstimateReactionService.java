package com.foremen.service.offer;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.EstimateDao;
import com.foremen.dao.OfferDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.OfferAction;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.service.OfferService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.estimate.recalc.EstimateChangedEvent;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * FOR-05-07 additional tasks 1–3 — the offer reaction to a committed estimate change (user decisions
 * 3a/3b):
 * <ul>
 *   <li><b>WITHDRAW</b> — the project's active offer the client has already seen ({@code SENT},
 *       {@code CHANGES_REQUESTED}, {@code COUNTERED}) is withdrawn, because its numbers no longer
 *       match the estimate.</li>
 *   <li><b>REVISE</b> — when the latest offer is {@code APPROVED}, a new {@code DRAFT} offer version
 *       ({@code revision = max + 1}) is prepared from the changed estimate.</li>
 *   <li>A {@code DRAFT} active offer is left as is: it is not visible to the client and its totals
 *       are live-referenced from the estimate, so it already reflects the change.</li>
 * </ul>
 *
 * <p>The transition is a system side effect of an estimate/room operation the acting user was
 * already permitted to perform, so it does NOT apply the offer executor-role gate: the withdraw is
 * driven through {@link OfferStatusMachine} with the system actor, which still refuses any exit from
 * a terminal state. Each reaction writes an audit row recording the acting user and the trigger.
 */
@Service
@RequiredArgsConstructor
public class OfferEstimateReactionService {

    /** The executor role the system acts as for the WITHDRAW transition (bypasses the user's role). */
    private static final String SYSTEM_ACTOR = "ADMIN";

    /** Active offers the client has seen — withdrawn when the estimate changes. */
    static final Set<OfferStatus> WITHDRAWABLE =
            EnumSet.of(OfferStatus.SENT, OfferStatus.CHANGES_REQUESTED, OfferStatus.COUNTERED);

    private static final List<OfferStatus> TERMINAL =
            List.of(OfferStatus.APPROVED, OfferStatus.REJECTED, OfferStatus.WITHDRAWN);

    static final String AUDIT_WITHDRAW = "SYSTEM_WITHDRAW";
    static final String AUDIT_REVISE = "SYSTEM_REVISE";

    private final OfferDao offerDao;
    private final EstimateDao estimateDao;
    private final OfferPackageDao offerPackageDao;
    private final OfferStatusMachine offerStatusMachine;
    private final OfferService offerService;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;

    /** The outcome of a reaction (never {@code NONE}). */
    public record Reaction(OfferEstimateImpact kind, Long projectId, Long offerId, Integer revision) {
    }

    /**
     * What an estimate change of {@code projectId} would do to its offer right now — the hint the
     * change-impact dialogs show before the user approves (decision 3c).
     */
    @Transactional(readOnly = true)
    public OfferEstimateImpact impactOf(Long projectId) {
        return classify(projectId);
    }

    /**
     * Applies the reaction for a committed estimate change in its own transaction (it runs after the
     * estimate write committed, so it can never roll that write back).
     *
     * @return the applied reaction, or {@code null} when no offer is affected
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reaction react(EstimateChangedEvent event) {
        Long projectId = event.projectId();
        if (projectId == null) {
            return null;
        }
        return switch (classify(projectId)) {
            case WITHDRAW -> withdraw(projectId, event);
            case REVISE -> revise(projectId, event);
            case NONE -> null;
        };
    }

    // --- internals -------------------------------------------------------------------------------

    private OfferEstimateImpact classify(Long projectId) {
        List<OfferEntity> active = offerDao.findByProjectIdAndStatusNotIn(projectId, TERMINAL);
        if (!active.isEmpty()) {
            return active.stream().anyMatch(o -> WITHDRAWABLE.contains(o.getStatus()))
                    ? OfferEstimateImpact.WITHDRAW
                    : OfferEstimateImpact.NONE; // an active DRAFT live-tracks the estimate
        }
        OfferEntity latest = latestOffer(projectId);
        return latest != null && latest.getStatus() == OfferStatus.APPROVED
                ? OfferEstimateImpact.REVISE
                : OfferEstimateImpact.NONE;
    }

    private Reaction withdraw(Long projectId, EstimateChangedEvent event) {
        OfferEntity offer = offerDao.findByProjectIdAndStatusNotIn(projectId, TERMINAL).stream()
                .filter(o -> WITHDRAWABLE.contains(o.getStatus()))
                .max(Comparator.comparing(OfferEntity::getId))
                .orElse(null);
        if (offer == null) {
            return null;
        }
        OfferStatus previous = offer.getStatus();
        // System actor: the acting user's own offer role is irrelevant (decision 3b); the machine still
        // rejects a terminal source, so a closed offer can never be reopened here.
        offer.setStatus(offerStatusMachine.transition(previous, OfferAction.WITHDRAW, SYSTEM_ACTOR));
        offerDao.save(offer);
        entityManager.flush();
        audit(offer, AUDIT_WITHDRAW, event, "{\"status\":\"" + previous + "\"}",
                "{\"status\":\"" + offer.getStatus() + "\"" + triggerJson(event) + "}");
        return new Reaction(OfferEstimateImpact.WITHDRAW, projectId, offer.getId(), offer.getRevision());
    }

    private Reaction revise(Long projectId, EstimateChangedEvent event) {
        OfferEntity approved = latestOffer(projectId);
        EstimateEntity estimate = estimateDao.findByProjectId(projectId).orElse(null);
        if (approved == null || estimate == null) {
            return null;
        }
        int nextRevision = offerDao.findByProjectIdOrderByIdAsc(projectId).stream()
                .map(OfferEntity::getRevision)
                .filter(Objects::nonNull)
                .max(Integer::compare)
                .orElse(0) + 1;

        OfferEntity offer = new OfferEntity();
        offer.setProject(estimate.getProject());
        offer.setEstimate(estimate);
        offer.setStatus(OfferStatus.DRAFT);
        offer.setRevision(nextRevision);
        offer.setSelectedPackage(packageFor(estimate, approved));
        offerService.recomputeTotals(offer);
        OfferEntity saved = offerDao.save(offer);
        entityManager.flush();

        audit(saved, AUDIT_REVISE, event,
                "{\"approvedOfferId\":" + approved.getId() + ",\"approvedRevision\":" + approved.getRevision() + "}",
                "{\"status\":\"" + saved.getStatus() + "\",\"revision\":" + saved.getRevision() + triggerJson(event) + "}");
        return new Reaction(OfferEstimateImpact.REVISE, projectId, saved.getId(), saved.getRevision());
    }

    /** The estimate's applied package (as {@code prepareOffer} seeds it), else the approved offer's. */
    private OfferPackageEntity packageFor(EstimateEntity estimate, OfferEntity approved) {
        String code = estimate.getAppliedPackageCode();
        if (code != null && !code.isBlank()) {
            OfferPackageEntity applied = offerPackageDao.findByCode(code).orElse(null);
            if (applied != null) {
                return applied;
            }
        }
        return approved.getSelectedPackage();
    }

    private OfferEntity latestOffer(Long projectId) {
        List<OfferEntity> all = offerDao.findByProjectIdOrderByIdAsc(projectId);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private void audit(OfferEntity offer, String operation, EstimateChangedEvent event, String before, String after) {
        AuditLogEntity row = new AuditLogEntity();
        row.setEntityClass(OfferEntity.class.getSimpleName());
        row.setEntityId(offer.getId());
        row.setOperation(operation);
        row.setPerformedBy(event.actingUserId() != null ? String.valueOf(event.actingUserId()) : "SYSTEM");
        row.setPerformedAt(LocalDateTime.now());
        row.setSnapshotBefore(before);
        row.setSnapshotAfter(after);
        auditLogDao.save(row);
    }

    private static String triggerJson(EstimateChangedEvent event) {
        return ",\"trigger\":\"ESTIMATE_CHANGED\",\"source\":\"" + event.trigger() + "\""
                + ",\"actingUserId\":" + (event.actingUserId() == null ? "null" : event.actingUserId())
                + ",\"estimateId\":" + (event.estimateId() == null ? "null" : event.estimateId());
    }
}
