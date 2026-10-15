package com.foremen.service.model.mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.WorkReportRoomModel;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.WorkReportServiceExtendedModel;
import com.foremen.service.model.WorkReportServiceModel;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-01 (Requirement 6.5; design §4.3) — service mapper for {@link WorkReportEntity}, following
 * the {@code AssortmentPositionServiceMapper} FK-resolution pattern: an <b>abstract class</b>
 * holding an injected {@link EntityManager} so the flat {@code projectId}/{@code workItemId}/
 * {@code workerId} become managed references via {@code getReference(...)} without a SELECT.
 *
 * <p><b>Write path.</b> {@code toCreateDaoModel}/{@code updateFields} set the {@code project},
 * {@code workItem}, and {@code worker} references and copy the flat scalars ({@code performedFrom},
 * {@code performedTo}, {@code note}). Everything the service owns is deliberately left to
 * {@code WorkReportService}: the {@code author} (the caller), the {@code status} / decision trail
 * (R4/R5), the frozen revenue valuation ({@code clientUnitPrice}/{@code totalQuantity}/
 * {@code revenueValue}, R6), and the owned {@code rooms} collection (PERCENT→absolute resolution +
 * {@code planQuantity} snapshot, R2.2/R2.6) — so those targets are {@code ignore}d here.
 *
 * <p><b>Read path.</b> {@code toServiceModel} maps the flat FK ids and the revenue/decision scalars;
 * {@link #resolveReadModel} then localizes the referenced work-item/room/worker/author/approver
 * names (RU when the request locale language is {@code ru}, else PL, with a {@code ru → pl →
 * code/label} fallback, R12.1), projects the per-room split, and leaves {@code costValue}/
 * {@code margin} as explicit {@code null}s (the cost side is FOR-06-10/11/06, R6.5).
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class WorkReportServiceMapper
        implements ServiceToDaoMapper<WorkReportEntity, WorkReportServiceModel,
        WorkReportServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected ProjectEntity projectRef(Long id) {
        return id == null ? null : entityManager.getReference(ProjectEntity.class, id);
    }

    protected WorkItemEntity workItemRef(Long id) {
        return id == null ? null : entityManager.getReference(WorkItemEntity.class, id);
    }

    protected UserEntity userRef(Long id) {
        return id == null ? null : entityManager.getReference(UserEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    // --- Write path ------------------------------------------------------------------------------

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", expression = "java(projectRef(source.getProjectId()))")
    @Mapping(target = "workItem", expression = "java(workItemRef(source.getWorkItemId()))")
    @Mapping(target = "worker", expression = "java(userRef(source.getWorkerId()))")
    @Mapping(target = "author", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "approvedBy", ignore = true)
    @Mapping(target = "approvedAt", ignore = true)
    @Mapping(target = "rejectionReason", ignore = true)
    @Mapping(target = "clientUnitPrice", ignore = true)
    @Mapping(target = "totalQuantity", ignore = true)
    @Mapping(target = "revenueValue", ignore = true)
    @Mapping(target = "rooms", ignore = true)
    public abstract WorkReportEntity toCreateDaoModel(WorkReportServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", ignore = true)
    @Mapping(target = "workItem", expression = "java(workItemRef(source.getWorkItemId()))")
    @Mapping(target = "worker", expression = "java(userRef(source.getWorkerId()))")
    @Mapping(target = "author", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "approvedBy", ignore = true)
    @Mapping(target = "approvedAt", ignore = true)
    @Mapping(target = "rejectionReason", ignore = true)
    @Mapping(target = "clientUnitPrice", ignore = true)
    @Mapping(target = "totalQuantity", ignore = true)
    @Mapping(target = "revenueValue", ignore = true)
    @Mapping(target = "rooms", ignore = true)
    public abstract void updateFields(WorkReportServiceExtendedModel source,
                                      @MappingTarget WorkReportEntity target);

    // --- Read path -------------------------------------------------------------------------------

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "workItemId", source = "workItem.id")
    @Mapping(target = "workItemName", ignore = true)
    @Mapping(target = "workerId", source = "worker.id")
    @Mapping(target = "workerName", ignore = true)
    @Mapping(target = "authorId", source = "author.id")
    @Mapping(target = "authorName", ignore = true)
    @Mapping(target = "approvedById", source = "approvedBy.id")
    @Mapping(target = "approvedByName", ignore = true)
    @Mapping(target = "costValue", ignore = true)
    @Mapping(target = "margin", ignore = true)
    @Mapping(target = "rooms", ignore = true)
    @Mapping(target = "mediaRefs", ignore = true)
    public abstract WorkReportServiceModel toServiceModel(WorkReportEntity source);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "workItemId", source = "workItem.id")
    @Mapping(target = "workerId", source = "worker.id")
    @Mapping(target = "rooms", ignore = true)
    @Mapping(target = "mediaRefs", ignore = true)
    public abstract WorkReportServiceExtendedModel toServiceExtendedModel(WorkReportEntity source);

    /**
     * Resolves the referenced display names with the per-request locale rule (RU when the request
     * locale language is {@code ru}, PL otherwise) and a {@code ru → pl → code/label} fallback, and
     * projects the owned per-room split into localized {@link WorkReportRoomModel} rows (R6.5,
     * R12.1). {@code costValue}/{@code margin} stay {@code null} (cost side is FOR-06-10/11/06).
     */
    @AfterMapping
    protected void resolveReadModel(@MappingTarget WorkReportServiceModel target,
                                    WorkReportEntity source) {
        boolean ru = isRussianLocale();

        WorkItemEntity workItem = source.getWorkItem();
        if (workItem != null) {
            target.setWorkItemName(
                    firstNonBlank(ru ? workItem.getNameRU() : workItem.getNamePL(),
                            ru ? workItem.getNamePL() : workItem.getNameRU(),
                            workItem.getCode()));
        }

        UserEntity worker = source.getWorker();
        if (worker != null) {
            target.setWorkerName(worker.getName());
        }

        UserEntity author = source.getAuthor();
        if (author != null) {
            target.setAuthorName(author.getName());
        }

        UserEntity approvedBy = source.getApprovedBy();
        if (approvedBy != null) {
            target.setApprovedByName(approvedBy.getName());
        }

        List<WorkReportRoomModel> rows = new ArrayList<>();
        if (source.getRooms() != null) {
            for (WorkReportRoomEntity row : source.getRooms()) {
                rows.add(new WorkReportRoomModel(
                        row.getId(),
                        row.getRoom() == null ? null : row.getRoom().getId(),
                        resolveRoomName(row.getRoom(), ru),
                        row.getQuantity(),
                        row.getPlanQuantity(),
                        row.getInputMode()));
            }
        }
        target.setRooms(rows);
    }

    /**
     * Localized room display name (ru → pl → label/code): a room has no own i18n name, so it reads
     * the room's {@code label} first, then the backing {@link RoomTypeEntity}'s localized name, then
     * the room type's {@code code} (R12.1).
     */
    private static String resolveRoomName(RoomEntity room, boolean ru) {
        if (room == null) {
            return null;
        }
        RoomTypeEntity type = room.getRoomType();
        String typeName = type == null ? null
                : firstNonBlank(ru ? type.getNameRU() : type.getNamePL(),
                        ru ? type.getNamePL() : type.getNameRU(),
                        type.getCode());
        return firstNonBlank(room.getLabel(), typeName);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
