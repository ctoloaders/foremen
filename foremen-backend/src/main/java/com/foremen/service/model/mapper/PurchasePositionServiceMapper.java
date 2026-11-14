package com.foremen.service.model.mapper;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.DeliveryCategoryEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.PurchasePositionServiceExtendedModel;
import com.foremen.service.model.PurchasePositionServiceModel;
import com.foremen.service.procurement.position.PositionStage;

import jakarta.persistence.EntityManager;

/**
 * Service mapper for purchase positions (FOR-11-00 R7/R9). Hand-written: references are resolved
 * with {@link EntityManager#getReference}; existence is validated by the service beforehand.
 *
 * <p>{@link #updateFields} applies only non-null values and never touches {@code project},
 * {@code kind}, {@code status}, {@code orderedQuantity}, {@code acceptedQuantity},
 * {@code deliveryCostShare}, {@code plannedDeliveryDate} or {@code purchaseRequest}: those are
 * immutable or system-owned (the service rejects attempts to supply them).
 */
@Component
public class PurchasePositionServiceMapper implements ServiceToDaoMapper<PurchasePositionEntity,
        PurchasePositionServiceModel, PurchasePositionServiceExtendedModel> {

    private final EntityManager entityManager;

    public PurchasePositionServiceMapper(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    @Override
    public PurchasePositionServiceModel toServiceModel(PurchasePositionEntity source) {
        if (source == null) {
            return null;
        }
        PurchasePositionServiceModel target = new PurchasePositionServiceModel();
        fill(source, target);
        return target;
    }

    @Override
    public PurchasePositionServiceExtendedModel toServiceExtendedModel(PurchasePositionEntity source) {
        if (source == null) {
            return null;
        }
        PurchasePositionServiceExtendedModel target = new PurchasePositionServiceExtendedModel();
        fill(source, target);
        return target;
    }

    private static void fill(PurchasePositionEntity s, PurchasePositionServiceModel t) {
        t.setId(s.getId());
        if (s.getProject() != null) {
            t.setProjectId(s.getProject().getId());
            t.setProjectName(s.getProject().getName());
        }
        t.setKind(s.getKind());
        t.setSourcingPath(s.getSourcingPath());
        t.setStatus(s.getStatus());
        t.setStatusCode(s.getStatus() == null ? null : PositionStage.statusCode(s.getStatus()));
        t.setPlannedMaterialId(s.getPlannedMaterial() == null ? null : s.getPlannedMaterial().getId());
        t.setActualMaterialId(s.getActualMaterial() == null ? null : s.getActualMaterial().getId());
        t.setDeliveryCategoryId(s.getDeliveryCategory() == null ? null : s.getDeliveryCategory().getId());
        t.setBlockNo(s.getBlockNo());
        t.setQuantity(s.getQuantity());
        t.setUnitId(s.getUnit() == null ? null : s.getUnit().getId());
        t.setOrderedQuantity(s.getOrderedQuantity());
        t.setAcceptedQuantity(s.getAcceptedQuantity());
        t.setPlannedPurchasePrice(s.getPlannedPurchasePrice());
        t.setSalePrice(s.getSalePrice());
        t.setActualPurchasePrice(s.getActualPurchasePrice());
        t.setPurchaseVatRateId(s.getPurchaseVatRate() == null ? null : s.getPurchaseVatRate().getId());
        t.setSaleVatRateId(s.getSaleVatRate() == null ? null : s.getSaleVatRate().getId());
        t.setDeliveryCostShare(s.getDeliveryCostShare());
        t.setNeedByDate(s.getNeedByDate());
        t.setOrderByDate(s.getOrderByDate());
        t.setDecisionDeadline(s.getDecisionDeadline());
        t.setPlannedDeliveryDate(s.getPlannedDeliveryDate());
        t.setPurchaseRequestId(s.getPurchaseRequest() == null ? null : s.getPurchaseRequest().getId());
        t.setEstimateReferenceId(s.getEstimateReference() == null ? null : s.getEstimateReference().getId());
        t.setNotes(s.getNotes());
    }

    @Override
    public PurchasePositionEntity toCreateDaoModel(PurchasePositionServiceExtendedModel s) {
        PurchasePositionEntity e = new PurchasePositionEntity();
        e.setProject(entityManager.getReference(ProjectEntity.class, s.getProjectId()));
        e.setKind(s.getKind());
        e.setSourcingPath(s.getSourcingPath());
        e.setStatus(PositionStatus.PLANNED);
        e.setPlannedMaterial(entityManager.getReference(FinishingMaterialEntity.class, s.getPlannedMaterialId()));
        e.setDeliveryCategory(ref(DeliveryCategoryEntity.class, s.getDeliveryCategoryId()));
        e.setBlockNo(s.getBlockNo());
        e.setQuantity(s.getQuantity());
        e.setUnit(entityManager.getReference(MeasurementUnitEntity.class, s.getUnitId()));
        e.setPlannedPurchasePrice(s.getPlannedPurchasePrice());
        e.setSalePrice(s.getSalePrice());
        e.setPurchaseVatRate(ref(VatRateEntity.class, s.getPurchaseVatRateId()));
        e.setSaleVatRate(ref(VatRateEntity.class, s.getSaleVatRateId()));
        e.setNeedByDate(s.getNeedByDate());
        e.setOrderByDate(s.getOrderByDate());
        e.setDecisionDeadline(s.getDecisionDeadline());
        e.setEstimateReference(ref(EstimateLineRoomMaterialEntity.class, s.getEstimateReferenceId()));
        e.setNotes(s.getNotes());
        return e;
    }

    @Override
    public void updateFields(PurchasePositionServiceExtendedModel s, PurchasePositionEntity t) {
        if (s.getSourcingPath() != null) {
            t.setSourcingPath(s.getSourcingPath());
        }
        if (s.getPlannedMaterialId() != null) {
            t.setPlannedMaterial(entityManager.getReference(FinishingMaterialEntity.class, s.getPlannedMaterialId()));
        }
        if (s.getActualMaterialId() != null) {
            t.setActualMaterial(entityManager.getReference(FinishingMaterialEntity.class, s.getActualMaterialId()));
        }
        if (s.getDeliveryCategoryId() != null) {
            t.setDeliveryCategory(entityManager.getReference(DeliveryCategoryEntity.class, s.getDeliveryCategoryId()));
        }
        if (s.getBlockNo() != null) {
            t.setBlockNo(s.getBlockNo());
        }
        if (s.getQuantity() != null) {
            t.setQuantity(s.getQuantity());
        }
        if (s.getUnitId() != null) {
            t.setUnit(entityManager.getReference(MeasurementUnitEntity.class, s.getUnitId()));
        }
        if (s.getPlannedPurchasePrice() != null) {
            t.setPlannedPurchasePrice(s.getPlannedPurchasePrice());
        }
        if (s.getSalePrice() != null) {
            t.setSalePrice(s.getSalePrice());
        }
        if (s.getActualPurchasePrice() != null) {
            t.setActualPurchasePrice(s.getActualPurchasePrice());
        }
        if (s.getPurchaseVatRateId() != null) {
            t.setPurchaseVatRate(entityManager.getReference(VatRateEntity.class, s.getPurchaseVatRateId()));
        }
        if (s.getSaleVatRateId() != null) {
            t.setSaleVatRate(entityManager.getReference(VatRateEntity.class, s.getSaleVatRateId()));
        }
        if (s.getNeedByDate() != null) {
            t.setNeedByDate(s.getNeedByDate());
        }
        if (s.getOrderByDate() != null) {
            t.setOrderByDate(s.getOrderByDate());
        }
        if (s.getDecisionDeadline() != null) {
            t.setDecisionDeadline(s.getDecisionDeadline());
        }
        if (s.getEstimateReferenceId() != null) {
            t.setEstimateReference(
                    entityManager.getReference(EstimateLineRoomMaterialEntity.class, s.getEstimateReferenceId()));
        }
        if (s.getNotes() != null) {
            t.setNotes(s.getNotes());
        }
    }

    private <T> T ref(Class<T> type, Long id) {
        return id == null ? null : entityManager.getReference(type, id);
    }
}
