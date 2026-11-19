package com.foremen.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkRelatedWorkDao;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkRelatedWorkEntity;
import com.foremen.exception.ForemenApiException;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Related works of a work (FOR-04-11 additional tasks 3-5): one-way links parent -> related, replaced
 * as a whole like the room-type attachment. Rules: a work never links to itself, a related work is
 * listed at most once, every id must exist. Cycles ({@code A -> B -> A}) are allowed in data but
 * harmless: the estimate expansion follows one level only, so they never loop.
 */
@Service
@RequiredArgsConstructor
public class WorkRelatedWorkService {

    private static final String NOT_FOUND = "error.entity.not.found";

    private final WorkRelatedWorkDao dao;
    private final WorkItemDao workItemDao;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<Long> getRelatedIds(Long workItemId) {
        requireWork(workItemId);
        return dao.findRelatedIds(workItemId);
    }

    /**
     * Replaces the related works of {@code workItemId} with exactly {@code relatedIds} ({@code null} or
     * empty clears them).
     *
     * @throws ForemenApiException 400 {@code error.work.related.self} / {@code error.work.related.duplicate},
     *                             404 {@code error.entity.not.found} for an unknown work
     */
    @Transactional
    public List<Long> setRelatedIds(Long workItemId, List<Long> relatedIds) {
        requireWork(workItemId);
        List<Long> requested = relatedIds == null ? List.of() : relatedIds;
        Set<Long> wanted = new LinkedHashSet<>();
        for (Long id : requested) {
            if (id == null) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, (Object) null);
            }
            if (id.equals(workItemId)) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.work.related.self");
            }
            if (!wanted.add(id)) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.work.related.duplicate", id);
            }
            requireWork(id);
        }
        // Remove links that are no longer wanted; unchanged links keep their row.
        Set<Long> existing = new LinkedHashSet<>();
        for (WorkRelatedWorkEntity link : dao.findByWorkItemId(workItemId)) {
            Long relatedId = link.getRelatedWorkItem().getId();
            if (wanted.contains(relatedId)) {
                existing.add(relatedId);
            } else {
                dao.delete(link);
            }
        }
        entityManager.flush();
        for (Long id : wanted) {
            if (existing.contains(id)) {
                continue;
            }
            WorkRelatedWorkEntity link = new WorkRelatedWorkEntity();
            link.setWorkItem(entityManager.getReference(WorkItemEntity.class, workItemId));
            link.setRelatedWorkItem(entityManager.getReference(WorkItemEntity.class, id));
            dao.save(link);
        }
        entityManager.flush();
        return dao.findRelatedIds(workItemId);
    }

    private void requireWork(Long id) {
        if (id == null || !workItemDao.existsById(id)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, id);
        }
    }
}
