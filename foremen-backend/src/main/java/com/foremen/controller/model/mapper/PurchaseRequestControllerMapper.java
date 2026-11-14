package com.foremen.controller.model.mapper;

import org.springframework.stereotype.Component;

import com.foremen.controller.model.PurchaseRequestCreateRequest;
import com.foremen.controller.model.PurchaseRequestCreateResponse;
import com.foremen.controller.model.PurchaseRequestDtoExtendedModel;
import com.foremen.controller.model.PurchaseRequestDtoModel;
import com.foremen.controller.model.PurchaseRequestUpdateRequest;
import com.foremen.controller.model.PurchaseRequestUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.PurchaseRequestServiceExtendedModel;
import com.foremen.service.model.PurchaseRequestServiceModel;

/** Hand-written controller mapper for purchase requests (FOR-11-00 R11); no Money_Field is mapped. */
@Component
public class PurchaseRequestControllerMapper implements ControllerToServiceMapper<
        PurchaseRequestServiceModel,
        PurchaseRequestServiceExtendedModel,
        PurchaseRequestDtoModel,
        PurchaseRequestDtoExtendedModel,
        PurchaseRequestCreateRequest,
        PurchaseRequestCreateResponse,
        PurchaseRequestUpdateRequest,
        PurchaseRequestUpdateResponse> {

    @Override
    public PurchaseRequestServiceExtendedModel toServiceExtendedModel(PurchaseRequestCreateRequest source) {
        PurchaseRequestServiceExtendedModel m = new PurchaseRequestServiceExtendedModel();
        m.setProjectId(source.projectId());
        m.setDeliveryCategoryId(source.deliveryCategoryId());
        m.setReferenceNumber(source.referenceNumber());
        m.setReferenceLink(source.referenceLink());
        m.setExpectedDeliveryDate(source.expectedDeliveryDate());
        m.setAssignedBuyerId(source.assignedBuyerId());
        m.setNotes(source.notes());
        return m;
    }

    @Override
    public PurchaseRequestServiceExtendedModel toUpdateServiceExtendedModel(PurchaseRequestUpdateRequest source) {
        PurchaseRequestServiceExtendedModel m = new PurchaseRequestServiceExtendedModel();
        m.setDeliveryCategoryId(source.deliveryCategoryId());
        m.setReferenceNumber(source.referenceNumber());
        m.setReferenceLink(source.referenceLink());
        m.setExpectedDeliveryDate(source.expectedDeliveryDate());
        m.setAssignedBuyerId(source.assignedBuyerId());
        m.setNotes(source.notes());
        return m;
    }

    @Override
    public PurchaseRequestDtoModel toDto(PurchaseRequestServiceModel s) {
        return s == null ? null : new PurchaseRequestDtoModel(s.getId(), s.getProjectId(), s.getDeliveryCategoryId(),
                s.getReferenceNumber(), s.getReferenceLink(), s.getExpectedDeliveryDate(), s.getAssignedBuyerId(),
                s.getAssignedBuyerName(), s.getNotes(), s.getStatus(), s.isOverdue(), s.getPositionCount());
    }

    @Override
    public PurchaseRequestDtoExtendedModel toExtendedDto(PurchaseRequestServiceExtendedModel s) {
        return s == null ? null : new PurchaseRequestDtoExtendedModel(s.getId(), s.getProjectId(),
                s.getDeliveryCategoryId(), s.getReferenceNumber(), s.getReferenceLink(), s.getExpectedDeliveryDate(),
                s.getAssignedBuyerId(), s.getAssignedBuyerName(), s.getNotes(), s.getStatus(), s.isOverdue(),
                s.getPositionCount());
    }

    @Override
    public PurchaseRequestCreateResponse toCreateResponse(PurchaseRequestServiceExtendedModel s) {
        return s == null ? null : new PurchaseRequestCreateResponse(s.getId(), s.getProjectId(),
                s.getDeliveryCategoryId(), s.getReferenceNumber(), s.getReferenceLink(), s.getExpectedDeliveryDate(),
                s.getAssignedBuyerId(), s.getAssignedBuyerName(), s.getNotes(), s.getStatus(), s.isOverdue(),
                s.getPositionCount());
    }

    @Override
    public PurchaseRequestUpdateResponse toUpdateResponse(PurchaseRequestServiceExtendedModel s) {
        return s == null ? null : new PurchaseRequestUpdateResponse(s.getId(), s.getProjectId(),
                s.getDeliveryCategoryId(), s.getReferenceNumber(), s.getReferenceLink(), s.getExpectedDeliveryDate(),
                s.getAssignedBuyerId(), s.getAssignedBuyerName(), s.getNotes(), s.getStatus(), s.isOverdue(),
                s.getPositionCount());
    }
}
