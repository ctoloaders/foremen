package com.foremen.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.controller.model.RefDto;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkPackageOverrideDao;
import com.foremen.dao.WorkPackageOverrideDao.WorkItemPackageMembership;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.WorkItemServiceExtendedModel;
import com.foremen.service.model.WorkItemServiceModel;
import com.foremen.service.model.mapper.WorkItemServiceMapper;

import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Getter
public class WorkItemService implements AdminService<
        WorkItemServiceModel, WorkItemServiceExtendedModel, WorkItemEntity, Long> {

    private final WorkItemDao dao;
    private final WorkItemServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final RoomTypeDao roomTypeDao;
    private final WorkPackageOverrideDao workPackageOverrideDao;
    private final Class<WorkItemEntity> daoModelClass = WorkItemEntity.class;

    /**
     * Overrides the generic paginated list to attach each row's offer-package membership
     * ({@code packages}) after the page is mapped. Membership is via {@code WorkPackageOverride}
     * ({@code member=true}), which is NOT a plain JPA M:N (the {@code member} flag rules it out), so
     * the field is a service-level post-processing concern rather than a mapper/entity-graph one.
     *
     * <p>To avoid a per-row N+1, the page's work-item ids are collected and their memberships loaded
     * in ONE grouped query ({@link WorkPackageOverrideDao#findMembershipRefsByWorkItemIdIn}); the rows
     * are grouped by work-item id and set onto each {@link WorkItemServiceModel}. The generic
     * filtering/sorting/paging (including the {@code packages.id} EXISTS filter registered by
     * {@code WorkItemPackagesQueryResolver}) is untouched — this only enriches the already-selected,
     * distinct page rows, so it never multiplies or reorders them.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<WorkItemServiceModel> find(Pageable pageable, String rawQuery) {
        Page<WorkItemServiceModel> page =
                AdminService.super.find(pageable, rawQuery);
        attachPackages(page.getContent());
        return page;
    }

    /**
     * Batch-loads and attaches the offer-package membership onto each row's {@code packages} list.
     * One grouped query for the whole page's work-item ids (no per-row N+1); the package name is
     * localized to the request locale (RU when the request locale language is {@code ru}, else PL —
     * PL fallback), mirroring the row's {@code name} localization rule.
     */
    private void attachPackages(List<WorkItemServiceModel> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream()
                .map(WorkItemServiceModel::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return;
        }

        boolean russian = isRussianLocale();
        Map<Long, List<RefDto>> byWorkItem = new LinkedHashMap<>();
        for (WorkItemPackageMembership m : workPackageOverrideDao.findMembershipRefsByWorkItemIdIn(ids)) {
            RefDto ref = new RefDto(m.getPackageId(), russian ? m.getNameRU() : m.getNamePL());
            byWorkItem.computeIfAbsent(m.getWorkItemId(), k -> new ArrayList<>()).add(ref);
        }

        for (WorkItemServiceModel row : rows) {
            List<RefDto> refs = byWorkItem.get(row.getId());
            row.setPackages(refs == null ? new ArrayList<>() : refs);
        }
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }

    /**
     * Reads the ids of the room types currently attached to a work item's Room_Type_Attachment
     * (FOR-05-05, R10.5). An empty list means the work has no attachment and therefore attaches to
     * ALL rooms on apply (R10.3).
     *
     * @param workItemId the work item id
     * @return the attached {@code RoomType} ids (never {@code null}; possibly empty)
     * @throws ForemenApiException {@code 404 error.entity.not.found} when the work item does not exist
     */
    @Transactional(readOnly = true)
    public List<Long> getRoomTypeIds(Long workItemId) {
        WorkItemEntity workItem = dao.findById(workItemId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, "error.entity.not.found", workItemId));
        return workItem.getRoomTypes().stream()
                .map(RoomTypeEntity::getId)
                .toList();
    }

    /**
     * Replaces a work item's Room_Type_Attachment with the referenced room types (FOR-05-05, R10.5).
     * The supplied list is a full REPLACE, not a delta: the work item's {@code roomTypes} collection
     * is set to exactly the referenced room types (deduplicated). A {@code null} or empty list clears
     * the attachment, meaning the work attaches to ALL rooms on apply (R10.3). The change is audited
     * as an {@code UPDATE} on the work item, consistent with the generic admin update path.
     *
     * @param workItemId  the work item id
     * @param roomTypeIds the desired attached {@code RoomType} ids ({@code null}/empty clears)
     * @return the resulting attached ids, preserving the requested order (deduplicated)
     * @throws ForemenApiException {@code 404 error.entity.not.found} when the work item or any
     *                             referenced room type does not exist
     */
    @Transactional
    public List<Long> setRoomTypeIds(Long workItemId, List<Long> roomTypeIds) {
        WorkItemEntity workItem = dao.findById(workItemId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, "error.entity.not.found", workItemId));

        String beforeSnapshot = serializeEntity(workItem);

        // Deduplicate while preserving the requested order; a null list clears the attachment.
        List<Long> requestedIds = roomTypeIds == null
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(roomTypeIds));

        Set<RoomTypeEntity> resolved = new LinkedHashSet<>();
        for (Long roomTypeId : requestedIds) {
            RoomTypeEntity roomType = roomTypeDao.findById(roomTypeId)
                    .orElseThrow(() -> new ForemenApiException(
                            HttpStatus.NOT_FOUND, "error.entity.not.found", roomTypeId));
            resolved.add(roomType);
        }

        workItem.getRoomTypes().clear();
        workItem.getRoomTypes().addAll(resolved);
        dao.save(workItem);
        entityManager.flush();
        saveAuditWithSnapshot(beforeSnapshot, workItem, "UPDATE");

        return requestedIds;
    }
}
