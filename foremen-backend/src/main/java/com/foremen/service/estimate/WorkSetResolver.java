package com.foremen.service.estimate;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.foremen.dao.StandardWorkSetItemDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkRelatedWorkDao;
import com.foremen.dao.model.WorkItemEntity;

import lombok.RequiredArgsConstructor;

/**
 * Catalog lookups behind the estimate's "work sets" (FOR-04-11 additional tasks 4 and 8): the related
 * works of a work and the standard work set of a room type. Kept out of
 * {@link EstimateAssignmentService} so that class' constructor does not grow.
 */
@Component
@RequiredArgsConstructor
public class WorkSetResolver {

    private final WorkRelatedWorkDao relatedWorkDao;
    private final StandardWorkSetItemDao standardWorkSetItemDao;
    private final WorkItemDao workItemDao;

    /**
     * The active works linked FROM {@code workItemId}, one level only (the links of those works are
     * never read here) and never the work itself.
     */
    public List<WorkItemEntity> relatedWorks(Long workItemId) {
        List<WorkItemEntity> out = new ArrayList<>();
        if (workItemId == null) {
            return out;
        }
        for (Long relatedId : relatedWorkDao.findRelatedIds(workItemId)) {
            if (workItemId.equals(relatedId)) {
                continue;
            }
            workItemDao.findById(relatedId).filter(WorkItemEntity::isActive).ifPresent(out::add);
        }
        return out;
    }

    /** The active works of the standard set of {@code roomTypeId}, in the order they were configured. */
    public List<WorkItemEntity> standardWorks(Long roomTypeId) {
        if (roomTypeId == null) {
            return List.of();
        }
        return standardWorkSetItemDao.findActiveWorksByRoomTypeId(roomTypeId);
    }
}
