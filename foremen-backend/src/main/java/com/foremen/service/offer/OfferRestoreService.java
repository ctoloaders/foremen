package com.foremen.service.offer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPriceEntity;
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
 * A4 (an {@link EstimateChangedEvent} with trigger {@code OFFER_RESTORE} is published, so the normal offer
 * reaction runs after commit), A5 (cells of rooms that no longer exist are skipped and listed).
 * Chosen materials are not part of the snapshot and are not restored.
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

    /** The restore result returned by {@code POST /api/offers/{offerId}/restore}. */
    public record RestoreResult(int restoredLines, int skippedWorks, List<SkippedRoom> skippedRooms,
                                EstimateTotals estimateTotals) {
    }

    public record SkippedRoom(Long roomId, String label) {
    }

    public record EstimateTotals(BigDecimal totalNet, BigDecimal totalVat, BigDecimal totalGross) {
    }

    @Transactional
    public RestoreResult restore(Long offerId) {
        OfferEntity offer = offerDao.findById(offerId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "offerId", offerId));
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
        Map<Long, String> snapshotRoomLabels = new LinkedHashMap<>();
        if (snapshot.rooms() != null) {
            snapshot.rooms().forEach(r -> snapshotRoomLabels.put(r.id(), r.label()));
        }

        // 1. Plan the new lines (read only), so a bad snapshot never leaves a half-deleted estimate.
        List<OfferSnapshot.Row> rows = new ArrayList<>();
        if (snapshot.groups() != null) {
            snapshot.groups().stream()
                    .filter(g -> g.rows() != null)
                    .forEach(g -> rows.addAll(g.rows()));
        }
        rows.sort(Comparator.comparing(r -> r.lineNo() != null ? r.lineNo() : Integer.MAX_VALUE));
        Map<Long, String> skippedRooms = new LinkedHashMap<>();
        int skippedWorks = 0;
        List<PlannedLine> planned = new ArrayList<>();
        for (OfferSnapshot.Row row : rows) {
            WorkItemEntity work = row.workItemId() != null
                    ? entityManager.find(WorkItemEntity.class, row.workItemId()) : null;
            if (work == null) {
                skippedWorks++;
                continue;
            }
            List<OfferSnapshot.Cell> cells = new ArrayList<>();
            if (row.cells() != null) {
                for (OfferSnapshot.Cell cell : row.cells()) {
                    if (cell.roomId() != null && projectRooms.containsKey(cell.roomId())) {
                        cells.add(cell);
                    } else {
                        skippedRooms.putIfAbsent(cell.roomId(), snapshotRoomLabels.get(cell.roomId()));
                    }
                }
            }
            if (cells.isEmpty()) {
                skippedWorks++;
                continue;
            }
            planned.add(new PlannedLine(row, work, cells));
        }

        // 2. Replace: bulk delete materials, room quantities and lines (children first, no reliance on DB cascade).
        entityManager.flush();
        entityManager.createQuery("delete from EstimateLineRoomMaterialEntity m where m.roomQty.id in "
                + "(select q.id from EstimateLineRoomQtyEntity q where q.line.estimate.id = :id)")
                .setParameter("id", estimateId).executeUpdate();
        entityManager.createQuery("delete from EstimateLineRoomQtyEntity q where q.line.estimate.id = :id")
                .setParameter("id", estimateId).executeUpdate();
        entityManager.createQuery("delete from EstimateLineEntity l where l.estimate.id = :id")
                .setParameter("id", estimateId).executeUpdate();
        entityManager.clear();

        EstimateEntity fresh = entityManager.find(EstimateEntity.class, estimateId);
        int lineNo = 0;
        for (PlannedLine p : planned) {
            lineNo++;
            OfferSnapshot.Row row = p.row();
            EstimateLineEntity line = new EstimateLineEntity();
            line.setEstimate(fresh);
            line.setWorkItem(p.work());
            MeasurementUnitEntity unit = p.work().getUnit();
            line.setUnit(unit);
            line.setLineNo(row.lineNo() != null ? row.lineNo() : lineNo);
            line.setComment(row.comment());
            BigDecimal base = row.baseUnitPrice() != null ? row.baseUnitPrice() : row.unitPrice();
            line.setUnitPrice(base != null ? base : BigDecimal.ZERO);
            line.setUnitPriceOverride(row.unitPriceOverride());
            if (row.workPriceId() != null) {
                WorkPriceEntity price = entityManager.find(WorkPriceEntity.class, row.workPriceId());
                line.setWorkPrice(price);
            }
            entityManager.persist(line);
            for (OfferSnapshot.Cell cell : p.cells()) {
                EstimateLineRoomQtyEntity qty = new EstimateLineRoomQtyEntity();
                qty.setLine(line);
                qty.setRoom(entityManager.getReference(RoomEntity.class, cell.roomId()));
                qty.setQuantity(cell.quantity());
                entityManager.persist(qty);
            }
        }
        fresh.setAppliedPackageCode(snapshot.appliedPackageCode());
        entityManager.flush();
        // Reload so the lines and their room quantities are read back from the database.
        entityManager.clear();
        fresh = entityManager.find(EstimateEntity.class, estimateId);

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

        return new RestoreResult(planned.size(), skippedWorks, skipped, totals);
    }

    private record PlannedLine(OfferSnapshot.Row row, WorkItemEntity work, List<OfferSnapshot.Cell> cells) {
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
