package com.foremen.service.model.mapper;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.DeliveryCategoryEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.RequestStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.PurchaseRequestServiceExtendedModel;
import com.foremen.service.model.PurchaseRequestServiceModel;
import com.foremen.service.procurement.PurchaseRequestOverdue;
import com.foremen.service.procurement.PurchaseRequestStatusDeriver;

import jakarta.persistence.EntityManager;

/**
 * Service mapper for purchase requests (FOR-11-00 R11). Derived {@code status}, {@code overdue} and
 * {@code positionCount} are assembled here from the request's positions (queried through the DAO so
 * a just-attached/detached position is always reflected) and the injected {@link Clock}.
 *
 * <p>Update uses null-ignore semantics; the project is never reassigned.
 */
@Component
public class PurchaseRequestServiceMapper implements ServiceToDaoMapper<PurchaseRequestEntity,
        PurchaseRequestServiceModel, PurchaseRequestServiceExtendedModel> {

    private final PurchasePositionDao positionDao;
    private final EntityManager entityManager;
    private final PurchaseRequestOverdue overdue;

    public PurchaseRequestServiceMapper(PurchasePositionDao positionDao, EntityManager entityManager, Clock clock) {
        this.positionDao = positionDao;
        this.entityManager = entityManager;
        this.overdue = new PurchaseRequestOverdue(clock);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    @Override
    public PurchaseRequestServiceModel toServiceModel(PurchaseRequestEntity source) {
        if (source == null) {
            return null;
        }
        PurchaseRequestServiceModel target = new PurchaseRequestServiceModel();
        fill(source, target);
        return target;
    }

    @Override
    public PurchaseRequestServiceExtendedModel toServiceExtendedModel(PurchaseRequestEntity source) {
        if (source == null) {
            return null;
        }
        PurchaseRequestServiceExtendedModel target = new PurchaseRequestServiceExtendedModel();
        fill(source, target);
        return target;
    }

    private void fill(PurchaseRequestEntity source, PurchaseRequestServiceModel target) {
        target.setId(source.getId());
        target.setProjectId(source.getProject() == null ? null : source.getProject().getId());
        target.setDeliveryCategoryId(source.getDeliveryCategory() == null ? null : source.getDeliveryCategory().getId());
        target.setReferenceNumber(source.getReferenceNumber());
        target.setReferenceLink(source.getReferenceLink());
        target.setExpectedDeliveryDate(source.getExpectedDeliveryDate());
        UserEntity buyer = source.getAssignedBuyer();
        target.setAssignedBuyerId(buyer == null ? null : buyer.getId());
        target.setAssignedBuyerName(buyer == null ? null : buyer.getName());
        target.setNotes(source.getNotes());

        List<PositionStatus> stages = source.getId() == null
                ? List.of()
                : positionDao.findByPurchaseRequestId(source.getId()).stream()
                        .map(PurchasePositionEntity::getStatus).toList();
        RequestStatus status = PurchaseRequestStatusDeriver.derive(stages);
        target.setStatus(status);
        target.setOverdue(overdue.isOverdue(source.getExpectedDeliveryDate(), status));
        target.setPositionCount(stages.size());
    }

    @Override
    public PurchaseRequestEntity toCreateDaoModel(PurchaseRequestServiceExtendedModel source) {
        PurchaseRequestEntity entity = new PurchaseRequestEntity();
        entity.setProject(entityManager.getReference(ProjectEntity.class, source.getProjectId()));
        entity.setDeliveryCategory(source.getDeliveryCategoryId() == null ? null
                : entityManager.getReference(DeliveryCategoryEntity.class, source.getDeliveryCategoryId()));
        entity.setReferenceNumber(source.getReferenceNumber());
        entity.setReferenceLink(source.getReferenceLink());
        entity.setExpectedDeliveryDate(source.getExpectedDeliveryDate());
        entity.setAssignedBuyer(source.getAssignedBuyerId() == null ? null
                : entityManager.getReference(UserEntity.class, source.getAssignedBuyerId()));
        entity.setNotes(source.getNotes());
        return entity;
    }

    @Override
    public void updateFields(PurchaseRequestServiceExtendedModel source, PurchaseRequestEntity target) {
        if (source.getDeliveryCategoryId() != null) {
            target.setDeliveryCategory(
                    entityManager.getReference(DeliveryCategoryEntity.class, source.getDeliveryCategoryId()));
        }
        if (source.getReferenceNumber() != null) {
            target.setReferenceNumber(source.getReferenceNumber());
        }
        if (source.getReferenceLink() != null) {
            target.setReferenceLink(source.getReferenceLink());
        }
        if (source.getExpectedDeliveryDate() != null) {
            target.setExpectedDeliveryDate(source.getExpectedDeliveryDate());
        }
        if (source.getAssignedBuyerId() != null) {
            target.setAssignedBuyer(entityManager.getReference(UserEntity.class, source.getAssignedBuyerId()));
        }
        if (source.getNotes() != null) {
            target.setNotes(source.getNotes());
        }
    }
}
