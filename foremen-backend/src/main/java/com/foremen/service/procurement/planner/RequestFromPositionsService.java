package com.foremen.service.procurement.planner;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.controller.model.FromPositionsRequest;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.PurchaseRequestService;
import com.foremen.service.model.PurchaseRequestServiceExtendedModel;

/**
 * Creates an internal purchase request from planner positions in one transaction (FOR-11-01 R7).
 *
 * <p>Reuses the FOR-11-00 {@link PurchaseRequestService}: {@code create} (project access, buyer and
 * category validation, {@code PurchaseRequestAssignedEvent} published by its {@code afterCreate} hook)
 * and {@code attachPositions} (same project, not in another request, not CANCELLED, planned-date
 * synchronizer). Any failure rolls the whole unit back, so no request is left behind and, because
 * the assignment event is consumed AFTER_COMMIT, no notification is sent.
 */
@Service
public class RequestFromPositionsService {

    static final String EMPTY_MESSAGE = "error.purchaseRequest.fromPositions.empty";
    static final String MIXED_PROJECTS_MESSAGE = "error.purchaseRequest.fromPositions.mixedProjects";
    private static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    private final PurchaseRequestService requestService;
    private final PurchasePositionDao positionDao;

    public RequestFromPositionsService(PurchaseRequestService requestService, PurchasePositionDao positionDao) {
        this.requestService = requestService;
        this.positionDao = positionDao;
    }

    @Transactional
    public PurchaseRequestServiceExtendedModel create(FromPositionsRequest request) {
        List<Long> ids = request.positionIds();
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(Objects::isNull)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, EMPTY_MESSAGE);
        }
        List<Long> distinctIds = List.copyOf(new LinkedHashSet<>(ids));

        Set<Long> projectIds = new LinkedHashSet<>();
        for (Long id : distinctIds) {
            PurchasePositionEntity position = positionDao.findById(id)
                    .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, id));
            projectIds.add(position.getProject().getId());
        }
        if (projectIds.size() > 1) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MIXED_PROJECTS_MESSAGE);
        }

        PurchaseRequestServiceExtendedModel model = new PurchaseRequestServiceExtendedModel();
        model.setProjectId(projectIds.iterator().next());
        model.setDeliveryCategoryId(request.deliveryCategoryId());
        model.setReferenceNumber(request.referenceNumber());
        model.setReferenceLink(request.referenceLink());
        model.setExpectedDeliveryDate(request.expectedDeliveryDate());
        model.setAssignedBuyerId(request.assignedBuyerId());
        model.setNotes(request.notes());

        PurchaseRequestServiceExtendedModel created = requestService.create(model);
        return requestService.attachPositions(created.getId(), distinctIds);
    }
}
