package com.foremen.controller.model.mapper;

import org.springframework.stereotype.Component;

import com.foremen.controller.model.PurchasePositionCreateRequest;
import com.foremen.controller.model.PurchasePositionDtoModel;
import com.foremen.controller.model.PurchasePositionUpdateRequest;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.PurchasePositionServiceExtendedModel;
import com.foremen.service.model.PurchasePositionServiceModel;
import com.foremen.service.procurement.position.PositionAudienceResolver;
import com.foremen.service.procurement.position.PositionMoney;
import com.foremen.service.procurement.position.PositionPriceVisibility;
import com.foremen.service.procurement.position.PositionPriceVisibility.Audience;

/**
 * Controller mapper for purchase positions (FOR-11-00 R9, R10.5). Every outbound DTO (list, detail,
 * create and update responses) passes the six Money_Fields through {@link PositionPriceVisibility}
 * for the caller's {@link Audience}; hidden fields are serialized as {@code null}.
 */
@Component
public class PurchasePositionControllerMapper implements ControllerToServiceMapper<
        PurchasePositionServiceModel,
        PurchasePositionServiceExtendedModel,
        PurchasePositionDtoModel,
        PurchasePositionDtoModel,
        PurchasePositionCreateRequest,
        PurchasePositionDtoModel,
        PurchasePositionUpdateRequest,
        PurchasePositionDtoModel> {

    private final PositionAudienceResolver audienceResolver;

    public PurchasePositionControllerMapper(PositionAudienceResolver audienceResolver) {
        this.audienceResolver = audienceResolver;
    }

    @Override
    public PurchasePositionServiceExtendedModel toServiceExtendedModel(PurchasePositionCreateRequest s) {
        PurchasePositionServiceExtendedModel m = new PurchasePositionServiceExtendedModel();
        m.setProjectId(s.projectId());
        m.setKind(s.kind());
        m.setSourcingPath(s.sourcingPath());
        m.setPlannedMaterialId(s.plannedMaterialId());
        m.setDeliveryCategoryId(s.deliveryCategoryId());
        m.setBlockNo(s.blockNo());
        m.setQuantity(s.quantity());
        m.setUnitId(s.unitId());
        m.setPlannedPurchasePrice(s.plannedPurchasePrice());
        m.setSalePrice(s.salePrice());
        m.setPurchaseVatRateId(s.purchaseVatRateId());
        m.setSaleVatRateId(s.saleVatRateId());
        m.setNeedByDate(s.needByDate());
        m.setOrderByDate(s.orderByDate());
        m.setDecisionDeadline(s.decisionDeadline());
        m.setEstimateReferenceId(s.estimateReferenceId());
        m.setNotes(s.notes());
        return m;
    }

    @Override
    public PurchasePositionServiceExtendedModel toUpdateServiceExtendedModel(PurchasePositionUpdateRequest s) {
        PurchasePositionServiceExtendedModel m = new PurchasePositionServiceExtendedModel();
        m.setProjectId(s.projectId());
        m.setKind(s.kind());
        m.setSourcingPath(s.sourcingPath());
        m.setStatus(s.status());
        m.setPlannedMaterialId(s.plannedMaterialId());
        m.setActualMaterialId(s.actualMaterialId());
        m.setDeliveryCategoryId(s.deliveryCategoryId());
        m.setBlockNo(s.blockNo());
        m.setQuantity(s.quantity());
        m.setUnitId(s.unitId());
        m.setOrderedQuantity(s.orderedQuantity());
        m.setAcceptedQuantity(s.acceptedQuantity());
        m.setPlannedPurchasePrice(s.plannedPurchasePrice());
        m.setSalePrice(s.salePrice());
        m.setActualPurchasePrice(s.actualPurchasePrice());
        m.setPurchaseVatRateId(s.purchaseVatRateId());
        m.setSaleVatRateId(s.saleVatRateId());
        m.setDeliveryCostShare(s.deliveryCostShare());
        m.setNeedByDate(s.needByDate());
        m.setOrderByDate(s.orderByDate());
        m.setDecisionDeadline(s.decisionDeadline());
        m.setPlannedDeliveryDate(s.plannedDeliveryDate());
        m.setEstimateReferenceId(s.estimateReferenceId());
        m.setNotes(s.notes());
        return m;
    }

    @Override
    public PurchasePositionDtoModel toDto(PurchasePositionServiceModel s) {
        return build(s);
    }

    @Override
    public PurchasePositionDtoModel toExtendedDto(PurchasePositionServiceExtendedModel s) {
        return build(s);
    }

    @Override
    public PurchasePositionDtoModel toCreateResponse(PurchasePositionServiceExtendedModel s) {
        return build(s);
    }

    @Override
    public PurchasePositionDtoModel toUpdateResponse(PurchasePositionServiceExtendedModel s) {
        return build(s);
    }

    /** Builds the DTO, applying the price-visibility policy for the current caller. */
    private PurchasePositionDtoModel build(PurchasePositionServiceModel s) {
        if (s == null) {
            return null;
        }
        PositionMoney raw = new PositionMoney(
                s.getPlannedPurchasePrice(), s.getActualPurchasePrice(), s.getPurchaseVatRateId(),
                s.getSalePrice(), s.getSaleVatRateId(), s.getDeliveryCostShare());
        PositionMoney visible = PositionPriceVisibility.apply(
                audienceResolver.current(), s.getSourcingPath(), s.getStatus(), raw);
        int variantCount = s.getVariantCount() == null ? 0 : s.getVariantCount();
        return new PurchasePositionDtoModel(
                s.getId(), s.getProjectId(), s.getProjectName(), s.getKind(), s.getSourcingPath(),
                s.getStatus(), s.getStatusCode(), s.getPlannedMaterialId(), s.getActualMaterialId(),
                s.getDeliveryCategoryId(), s.getBlockNo(), s.getQuantity(), s.getUnitId(),
                s.getOrderedQuantity(), s.getAcceptedQuantity(),
                visible.plannedPurchasePrice(), visible.salePrice(), visible.actualPurchasePrice(),
                visible.purchaseVatRateId(), visible.saleVatRateId(), visible.deliveryCostShare(),
                s.getNeedByDate(), s.getOrderByDate(), s.getDecisionDeadline(), s.getPlannedDeliveryDate(),
                s.getPurchaseRequestId(), s.getEstimateReferenceId(), s.getNotes(),
                variantCount, variantCount > 0);
    }
}
