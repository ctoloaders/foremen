package com.foremen.service.offer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.OfferDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.estimate.DraftGateGuard;
import com.foremen.service.estimate.EstimateRecomputeService;
import com.foremen.service.estimate.recalc.EstimateChangedEvent;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * FOR-05-07b task 3.7: restores the live estimate from an offer snapshot (version 2) in one transaction.
 *
 * <p>Assumptions: A1 (whole estimate replaced, refused with 409 {@code error.estimate.locked} when not
 * DRAFT), A3 (a missing or version 1 snapshot is refused, 404 {@code error.offer.snapshot.missing}),
 * D3 (an {@link EstimateChangedEvent} with trigger {@code OFFER_RESTORE} is published for the collaboration
 * broadcast; offers do not react to it), A5 (cells of rooms that no longer exist are skipped and listed).
 * Version 3 snapshots restore their stored material lines (FOR-05-04 R23.4); version 2 snapshots restore
 * without materials.
 */
@Service
@RequiredArgsConstructor
public class OfferRestoreService {

    static final String SNAPSHOT_MISSING = "error.offer.snapshot.missing";
    static final String NOT_FOUND = "error.entity.not.found";
    static final String AUDIT_RESTORE = "RESTORE_ESTIMATE";
    static final int MIN_SNAPSHOT_VERSION = 2;

    private final OfferDao offerDao;
    private final RoomDao roomDao;
    private final DraftGateGuard draftGateGuard;
    private final EstimateRecomputeService estimateRecomputeService;
    private final AuditLogDao auditLogDao;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityManager entityManager;
    private final EstimateReplaceHelper replaceHelper;

    /** The restore result returned by {@code POST /api/offers/{offerId}/restore}. */
    public record RestoreResult(int restoredLines, int skippedWorks, List<SkippedRoom> skippedRooms,
                                EstimateTotals estimateTotals, int skippedMaterials) {
        /** Shape without the material count (FOR-05-04 task 3.12 added it). */
        public RestoreResult(int restoredLines, int skippedWorks, List<SkippedRoom> skippedRooms,
                             EstimateTotals estimateTotals) {
            this(restoredLines, skippedWorks, skippedRooms, estimateTotals, 0);
        }
    }

    public record SkippedRoom(Long roomId, String label) {
    }

    public record EstimateTotals(BigDecimal totalNet, BigDecimal totalVat, BigDecimal totalGross) {
    }

    /** FOR-05-04 (R4.1): AI project lock; optional (setter-injected) so unit tests may omit it. */
    private com.foremen.service.estimateai.AiProjectLockGuard aiLockGuard;

    @org.springframework.beans.factory.annotation.Autowired
    public void setAiLockGuard(com.foremen.service.estimateai.AiProjectLockGuard aiLockGuard) {
        this.aiLockGuard = aiLockGuard;
    }

    @Transactional
    public RestoreResult restore(Long offerId) {
        OfferEntity offer = offerDao.findById(offerId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "offerId", offerId));
        if (offer.getProject() != null) {
            com.foremen.service.estimateai.AiProjectLockGuard.assertUnlocked(aiLockGuard, offer.getProject().getId());
        }
        OfferSnapshot snapshot = offer.getSnapshot();
        if (snapshot == null || snapshot.version() < MIN_SNAPSHOT_VERSION) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, SNAPSHOT_MISSING, "offerId", offerId);
        }
        EstimateEntity estimate = offer.getEstimate();
        if (estimate == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "offerId", offerId);
        }
        draftGateGuard.assertDraft(estimate);

        Long estimateId = estimate.getId();
        Long projectId = estimate.getProject().getId();
        int linesBefore = estimate.getLines().size();
        BigDecimal netBefore = estimate.getTotalNet();

        Map<Long, RoomEntity> projectRooms = new LinkedHashMap<>();
        for (RoomEntity room : roomDao.findByProjectId(projectId)) {
            projectRooms.put(room.getId(), room);
        }
        // 1. Plan the new lines (read only), so a bad snapshot never leaves a half-deleted estimate.
        EstimateReplaceHelper.Plan plan = replaceHelper.plan(snapshot, projectRooms);
        List<EstimateReplaceHelper.PlannedLine> planned = plan.lines();
        Map<Long, String> skippedRooms = plan.skippedRooms();
        int skippedWorks = plan.skippedWorks();

        // 2. Replace: delete the old lines and insert the planned ones (children first, no reliance on DB cascade).
        EstimateEntity fresh = replaceHelper.replace(estimateId, planned, snapshot.appliedPackageCode());

        // 3. Derive quantities and totals the normal way.
        estimateRecomputeService.recomputeEstimate(fresh);
        entityManager.flush();

        EstimateTotals totals = new EstimateTotals(fresh.getTotalNet(), fresh.getTotalVat(), fresh.getTotalGross());
        List<SkippedRoom> skipped = skippedRooms.entrySet().stream()
                .map(e -> new SkippedRoom(e.getKey(), e.getValue())).toList();

        // 4. Audit and estimate-change reaction (A4).
        Long actingUserId = currentUserId();
        AuditLogEntity audit = new AuditLogEntity();
        audit.setEntityClass(OfferEntity.class.getSimpleName());
        audit.setEntityId(offerId);
        audit.setOperation(AUDIT_RESTORE);
        audit.setPerformedBy(actingUserId != null ? String.valueOf(actingUserId) : currentName());
        audit.setPerformedAt(LocalDateTime.now());
        audit.setSnapshotBefore("{\"lines\":" + linesBefore + ",\"totalNet\":" + netBefore + "}");
        audit.setSnapshotAfter("{\"lines\":" + planned.size() + ",\"skippedWorks\":" + skippedWorks
                + ",\"skippedRooms\":" + skipped.size() + ",\"totalNet\":" + totals.totalNet()
                + ",\"totalGross\":" + totals.totalGross()
                + ",\"appliedPackageCode\":" + (snapshot.appliedPackageCode() == null
                        ? "null" : "\"" + snapshot.appliedPackageCode().replace("\"", "") + "\"") + "}");
        auditLogDao.save(audit);

        eventPublisher.publishEvent(new EstimateChangedEvent(
                projectId, estimateId, actingUserId, EstimateChangedEvent.Trigger.OFFER_RESTORE));

        return new RestoreResult(planned.size(), skippedWorks, skipped, totals, plan.skippedMaterials());
    }

    private static String currentName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getName() != null ? auth.getName() : "SYSTEM";
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        try {
            return Long.valueOf(auth.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
