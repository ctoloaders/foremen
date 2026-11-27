package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPriceEntity;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * The reusable replace step of an estimate restore (FOR-05-04-estimate-AI task 3.2), extracted unchanged
 * from {@link OfferRestoreService#restore}: a read-only planning part that maps a snapshot onto the
 * project's current rooms and works, and a delete/insert part that replaces the estimate's lines, room
 * quantities and materials with the plan. {@code OfferRestoreService} and the AI scratch service both
 * call it. Both parts run inside the caller's transaction; {@link #replace} clears the persistence context.
 *
 * <p>Task 3.12 (Requirement 23.4): the cell's material lines are recreated from the snapshot's
 * {@code materials} (override, chosen product, price range, package flag), not re-seeded from the norms.
 */
@Component
@RequiredArgsConstructor
public class EstimateReplaceHelper {

    private final EntityManager entityManager;

    /** One planned estimate line: the snapshot row, its resolved work and the cells that still have a room. */
    public record PlannedLine(OfferSnapshot.Row row, WorkItemEntity work, List<OfferSnapshot.Cell> cells) {
    }

    /**
     * Result of {@link #plan}: lines to create plus what had to be skipped. {@code skippedMaterials}
     * counts material lines whose type is gone (skipped) or whose chosen product is gone (restored as a
     * placeholder).
     */
    public record Plan(List<PlannedLine> lines, int skippedWorks, Map<Long, String> skippedRooms,
                       int skippedMaterials) {
        public Plan(List<PlannedLine> lines, int skippedWorks, Map<Long, String> skippedRooms) {
            this(lines, skippedWorks, skippedRooms, 0);
        }
    }

    /** A snapshot material line resolved against the current catalog (Requirement 23.4). */
    private record ResolvedMaterial(ConsumptionBranch branch,
                                    ConstructionMaterialTypeEntity constructionType,
                                    MaterialTypeEntity finishingType,
                                    ConstructionMaterialEntity concreteConstruction,
                                    FinishingMaterialEntity concreteFinishing,
                                    ConstructionMaterialEntity sourceConstruction,
                                    FinishingMaterialEntity sourceFinishing,
                                    boolean usable, boolean productDropped) {
        boolean degraded() {
            return !usable || productDropped;
        }
    }

    private ResolvedMaterial resolveMaterial(OfferSnapshot.MaterialLine m) {
        ConsumptionBranch branch = parseBranch(m.branch());
        ConstructionMaterialTypeEntity constructionType = null;
        MaterialTypeEntity finishingType = null;
        ConstructionMaterialEntity concreteConstruction = null;
        FinishingMaterialEntity concreteFinishing = null;
        boolean usable = false;
        boolean dropped = false;
        if (branch == ConsumptionBranch.construction && m.constructionTypeId() != null) {
            constructionType = entityManager.find(ConstructionMaterialTypeEntity.class, m.constructionTypeId());
            usable = constructionType != null;
        } else if (branch == ConsumptionBranch.finishing && m.finishingTypeId() != null) {
            finishingType = entityManager.find(MaterialTypeEntity.class, m.finishingTypeId());
            usable = finishingType != null;
        }
        ConstructionMaterialEntity sourceConstruction = null;
        FinishingMaterialEntity sourceFinishing = null;
        if (usable) {
            if (m.concreteConstructionMaterialId() != null) {
                concreteConstruction = entityManager.find(ConstructionMaterialEntity.class,
                        m.concreteConstructionMaterialId());
                dropped |= concreteConstruction == null;
            }
            if (m.concreteFinishingMaterialId() != null) {
                concreteFinishing = entityManager.find(FinishingMaterialEntity.class,
                        m.concreteFinishingMaterialId());
                dropped |= concreteFinishing == null;
            }
            if (m.sourceConstructionMaterialId() != null) {
                sourceConstruction = entityManager.find(ConstructionMaterialEntity.class,
                        m.sourceConstructionMaterialId());
            }
            if (m.sourceFinishingMaterialId() != null) {
                sourceFinishing = entityManager.find(FinishingMaterialEntity.class,
                        m.sourceFinishingMaterialId());
            }
        }
        return new ResolvedMaterial(branch, constructionType, finishingType, concreteConstruction,
                concreteFinishing, sourceConstruction, sourceFinishing, usable, dropped);
    }

    private static ConsumptionBranch parseBranch(String branch) {
        if (branch == null) {
            return null;
        }
        try {
            return ConsumptionBranch.valueOf(branch);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Plans the new lines (read only), so a bad snapshot never leaves a half-deleted estimate. */
    public Plan plan(OfferSnapshot snapshot, Map<Long, RoomEntity> projectRooms) {
        Map<Long, String> snapshotRoomLabels = new LinkedHashMap<>();
        if (snapshot.rooms() != null) {
            snapshot.rooms().forEach(r -> snapshotRoomLabels.put(r.id(), r.label()));
        }
        List<OfferSnapshot.Row> rows = new ArrayList<>();
        if (snapshot.groups() != null) {
            snapshot.groups().stream()
                    .filter(g -> g.rows() != null)
                    .forEach(g -> rows.addAll(g.rows()));
        }
        rows.sort(Comparator.comparing(r -> r.lineNo() != null ? r.lineNo() : Integer.MAX_VALUE));
        Map<Long, String> skippedRooms = new LinkedHashMap<>();
        int skippedWorks = 0;
        int skippedMaterials = 0;
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
            for (OfferSnapshot.Cell cell : cells) {
                if (cell.materials() == null) {
                    continue;
                }
                for (OfferSnapshot.MaterialLine m : cell.materials()) {
                    if (resolveMaterial(m).degraded()) {
                        skippedMaterials++;
                    }
                }
            }
            planned.add(new PlannedLine(row, work, cells));
        }
        return new Plan(planned, skippedWorks, skippedRooms, skippedMaterials);
    }

    /** Deletes every line, room quantity and material of the estimate (children first), then clears the context. */
    public void deleteAll(Long estimateId) {
        entityManager.flush();
        entityManager.createQuery("delete from EstimateLineRoomMaterialEntity m where m.roomQty.id in "
                + "(select q.id from EstimateLineRoomQtyEntity q where q.line.estimate.id = :id)")
                .setParameter("id", estimateId).executeUpdate();
        entityManager.createQuery("delete from EstimateLineRoomQtyEntity q where q.line.estimate.id = :id")
                .setParameter("id", estimateId).executeUpdate();
        entityManager.createQuery("delete from EstimateLineEntity l where l.estimate.id = :id")
                .setParameter("id", estimateId).executeUpdate();
        entityManager.clear();
    }

    /**
     * Replaces the estimate's content with {@code planned}: deletes the old lines, inserts the new ones,
     * sets the applied package code, flushes and clears, and returns the estimate reloaded from the database.
     */
    public EstimateEntity replace(Long estimateId, List<PlannedLine> planned, String appliedPackageCode) {
        deleteAll(estimateId);

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
                // FOR-05-04 (R21.3): a restored override stays an override (version 2 cells carry none)
                // and the AI explanation is copied into estimate_line_room_qty.ai_meta.
                qty.setVolumeOverridden(Boolean.TRUE.equals(cell.volumeOverridden()));
                qty.setAiMeta(cell.ai());
                entityManager.persist(qty);
                restoreMaterials(qty, cell.materials());
            }
        }
        fresh.setAppliedPackageCode(appliedPackageCode);
        entityManager.flush();
        // Reload so the lines and their room quantities are read back from the database.
        entityManager.clear();
        return entityManager.find(EstimateEntity.class, estimateId);
    }

    /**
     * FOR-05-04 (Requirement 23.4): recreates the cell's material lines from the snapshot (no re-seeding
     * from the norms), keeping the override, the chosen product and the package flag. A line whose type is
     * gone is skipped, one whose chosen product is gone becomes a placeholder. A version 2 cell
     * ({@code materials == null}) creates nothing.
     */
    private void restoreMaterials(EstimateLineRoomQtyEntity qty, List<OfferSnapshot.MaterialLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        for (OfferSnapshot.MaterialLine m : lines) {
            ResolvedMaterial r = resolveMaterial(m);
            if (!r.usable()) {
                continue;
            }
            EstimateLineRoomMaterialEntity material = new EstimateLineRoomMaterialEntity();
            material.setRoomQty(qty);
            material.setBranch(r.branch());
            material.setConstructionType(r.constructionType());
            material.setFinishingType(r.finishingType());
            material.setNormQty(m.normQty());
            material.setConsumptionBasis(parseBasis(m.consumptionBasis()));
            material.setRangeMin(m.rangeMin());
            material.setRangeMax(m.rangeMax());
            material.setQtyOverridden(m.overridden());
            material.setManualQty(m.overridden() ? m.manualQty() : null);
            material.setConcreteConstructionMaterial(r.concreteConstruction());
            material.setConcreteFinishingMaterial(r.concreteFinishing());
            boolean hasProduct = r.concreteConstruction() != null || r.concreteFinishing() != null;
            material.setConcreteNet(hasProduct ? m.concreteNet() : null);
            material.setAppliedFromPackage(Boolean.TRUE.equals(m.appliedFromPackage()));
            material.setSourceConstructionMaterial(r.sourceConstruction());
            material.setSourceFinishingMaterial(r.sourceFinishing());
            entityManager.persist(material);
            if (qty.getMaterials() != null) {
                qty.getMaterials().add(material);
            }
        }
    }

    private static ConsumptionBasis parseBasis(String basis) {
        if (basis == null) {
            return ConsumptionBasis.PER_UNIT;
        }
        try {
            return ConsumptionBasis.valueOf(basis);
        } catch (IllegalArgumentException e) {
            return ConsumptionBasis.PER_UNIT;
        }
    }
}
