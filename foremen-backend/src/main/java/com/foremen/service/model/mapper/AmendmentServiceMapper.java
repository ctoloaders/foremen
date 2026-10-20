package com.foremen.service.model.mapper;

import java.math.BigDecimal;
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
import com.foremen.dao.model.AmendmentLineEntity;
import com.foremen.dao.model.EstimateAmendmentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.AmendmentLineServiceModel;
import com.foremen.service.model.AmendmentServiceExtendedModel;
import com.foremen.service.model.AmendmentServiceModel;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-08 (Requirements 1.1, 1.4, 4.3; design §Backend components) — service mapper for
 * {@link EstimateAmendmentEntity}, following the {@code WorkReportServiceMapper} /
 * {@code AssortmentPositionServiceMapper} convention: an <b>abstract class</b> holding an injected
 * {@link EntityManager} so the flat {@code projectId} becomes a managed reference via
 * {@code getReference(...)} without a SELECT.
 *
 * <p><b>Write path.</b> {@code toCreateDaoModel}/{@code updateFields} set the {@code project}
 * reference and copy the free-text {@code note}; everything the service owns is left to
 * {@code AmendmentService}: the {@code seqNo}, the {@code status} ({@code OPEN}), the owned
 * {@code lines} collection (added/merged through the dedicated add-line path, R3/R5), and the
 * {@code effectiveDate} / {@code signableDocument} (FOR-06-09 only, R7.4) — so those targets are
 * {@code ignore}d here.
 *
 * <p><b>Read path.</b> {@code toServiceModel} maps the flat {@code projectId} and the scalar
 * {@code seqNo}/{@code status}/{@code effectiveDate}/{@code note} and {@link #resolveListRow}
 * computes the {@code lineCount}; {@code toServiceExtendedModel} + {@link #resolveExtendedReadModel}
 * project the owned change lines (each with its referenced work-item / room / work-category localized
 * names, ru → pl → code/label, R11.1/R11.2) and the informational aggregate
 * {@code totalAddedValue} = {@code Σ deltaQuantity × extraUnitPrice} over the cost-bearing lines
 * (R4.3).
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class AmendmentServiceMapper
        implements ServiceToDaoMapper<EstimateAmendmentEntity, AmendmentServiceModel,
        AmendmentServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected ProjectEntity projectRef(Long id) {
        return id == null ? null : entityManager.getReference(ProjectEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    // --- Write path ------------------------------------------------------------------------------

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", expression = "java(projectRef(source.getProjectId()))")
    @Mapping(target = "seqNo", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "effectiveDate", ignore = true)
    @Mapping(target = "signableDocument", ignore = true)
    @Mapping(target = "lines", ignore = true)
    public abstract EstimateAmendmentEntity toCreateDaoModel(AmendmentServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", ignore = true)
    @Mapping(target = "seqNo", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "effectiveDate", ignore = true)
    @Mapping(target = "signableDocument", ignore = true)
    @Mapping(target = "lines", ignore = true)
    public abstract void updateFields(AmendmentServiceExtendedModel source,
                                      @MappingTarget EstimateAmendmentEntity target);

    // --- Read path -------------------------------------------------------------------------------

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "lineCount", ignore = true)
    public abstract AmendmentServiceModel toServiceModel(EstimateAmendmentEntity source);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "lines", ignore = true)
    @Mapping(target = "totalAddedValue", ignore = true)
    public abstract AmendmentServiceExtendedModel toServiceExtendedModel(EstimateAmendmentEntity source);

    /** List row: the scalar fields auto-map by name; this fills the {@code lineCount} (R1.1). */
    @AfterMapping
    protected void resolveListRow(@MappingTarget AmendmentServiceModel target,
                                  EstimateAmendmentEntity source) {
        target.setLineCount(source.getLines() == null ? 0 : source.getLines().size());
    }

    /**
     * Single-read projection: resolve the owned change lines with their referenced localized names
     * and compute the informational {@code totalAddedValue} = {@code Σ deltaQuantity × extraUnitPrice}
     * over the cost-bearing lines (R4.3). The scalar read fields (seqNo, status, effectiveDate, note)
     * auto-map by name from the entity.
     */
    @AfterMapping
    protected void resolveExtendedReadModel(@MappingTarget AmendmentServiceExtendedModel target,
                                            EstimateAmendmentEntity source) {
        boolean ru = isRussianLocale();

        List<AmendmentLineServiceModel> rows = new ArrayList<>();
        BigDecimal totalAddedValue = BigDecimal.ZERO;
        if (source.getLines() != null) {
            for (AmendmentLineEntity line : source.getLines()) {
                rows.add(toLineModel(line, ru));
                BigDecimal lineValue = lineAddedValue(line);
                if (lineValue != null) {
                    totalAddedValue = totalAddedValue.add(lineValue);
                }
            }
        }
        target.setLines(rows);
        target.setTotalAddedValue(totalAddedValue);
    }

    /** Projects one line entity into its service model with the referenced names resolved. */
    private static AmendmentLineServiceModel toLineModel(AmendmentLineEntity line, boolean ru) {
        AmendmentLineServiceModel model = new AmendmentLineServiceModel();
        model.setId(line.getId());
        model.setChangeType(line.getChangeType());
        model.setDeltaQuantity(line.getDeltaQuantity());
        model.setExtraUnitPrice(line.getExtraUnitPrice());
        model.setScheduleDeltaHalfDays(line.getScheduleDeltaHalfDays());
        model.setDescription(line.getDescription());

        WorkItemEntity workItem = line.getWorkItem();
        if (workItem != null) {
            model.setWorkItemId(workItem.getId());
            model.setWorkItemName(
                    firstNonBlank(ru ? workItem.getNameRU() : workItem.getNamePL(),
                            ru ? workItem.getNamePL() : workItem.getNameRU(),
                            workItem.getCode()));
        }

        RoomEntity room = line.getRoom();
        if (room != null) {
            model.setRoomId(room.getId());
            model.setRoomName(resolveRoomName(room, ru));
        }

        WorkCategoryEntity category = line.getWorkCategory();
        if (category != null) {
            model.setWorkCategoryId(category.getId());
            model.setWorkCategoryName(
                    firstNonBlank(ru ? category.getNameRU() : category.getNamePL(),
                            ru ? category.getNamePL() : category.getNameRU(),
                            category.getCode()));
        }
        return model;
    }

    /**
     * The per-line added value {@code deltaQuantity × extraUnitPrice} for the aggregate (R4.3);
     * {@code null} when the line is not cost-bearing (a SCHEDULE_CHANGE, or missing either factor).
     */
    private static BigDecimal lineAddedValue(AmendmentLineEntity line) {
        BigDecimal qty = line.getDeltaQuantity();
        BigDecimal price = line.getExtraUnitPrice();
        if (qty == null || price == null) {
            return null;
        }
        return qty.multiply(price);
    }

    /**
     * Localized room display name (ru → pl → label/code): a room has no own i18n name, so it reads
     * the room's {@code label} first, then the backing {@link RoomTypeEntity}'s localized name, then
     * the room type's {@code code} (R11.2).
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
