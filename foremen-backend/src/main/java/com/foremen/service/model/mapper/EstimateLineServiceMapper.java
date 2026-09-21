package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.EstimateLineServiceExtendedModel;
import com.foremen.service.model.EstimateLineServiceModel;
import jakarta.persistence.EntityManager;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Service mapper for {@link EstimateLineEntity} (FOR-05-03, Requirement 2), following the FOR-04
 * {@code RoomServiceMapper} FK-resolution pattern: an <b>abstract class</b> holding an injected
 * {@link EntityManager} so the flat {@code estimateId}/{@code workItemId}/{@code workPriceId}/
 * {@code unitId} become managed references via {@code getReference(...)} without a SELECT.
 *
 * <p>EstimateLine has no own i18n {@code name}, so {@link #getI18nSupportedProperties()} returns an
 * empty set. The read model resolves the localized {@code workItemName}/{@code unitName} in an
 * {@code @AfterMapping}, mirroring {@code RoomServiceMapper}.
 *
 * <p><b>Derived columns are ignored inbound (R2.6, R2.7, R8.4, task 5.2):</b> {@code quantity} and
 * {@code valueNet} are computed exclusively by {@code EstimateRecomputeService}; a client payload can
 * set them on the write model, but {@link #toCreateDaoModel} and {@link #updateFields} never copy
 * them onto the entity, so they have no effect.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class EstimateLineServiceMapper
        implements ServiceToDaoMapper<EstimateLineEntity, EstimateLineServiceModel, EstimateLineServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected EstimateEntity estimateRef(Long id) {
        return id == null ? null : entityManager.getReference(EstimateEntity.class, id);
    }

    protected WorkItemEntity workItemRef(Long id) {
        return id == null ? null : entityManager.getReference(WorkItemEntity.class, id);
    }

    protected WorkPriceEntity workPriceRef(Long id) {
        return id == null ? null : entityManager.getReference(WorkPriceEntity.class, id);
    }

    protected MeasurementUnitEntity unitRef(Long id) {
        return id == null ? null : entityManager.getReference(MeasurementUnitEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "estimate", expression = "java(estimateRef(source.getEstimateId()))")
    @Mapping(target = "workItem", expression = "java(workItemRef(source.getWorkItemId()))")
    @Mapping(target = "workPrice", expression = "java(workPriceRef(source.getWorkPriceId()))")
    @Mapping(target = "unit", expression = "java(unitRef(source.getUnitId()))")
    @Mapping(target = "quantity", ignore = true)
    @Mapping(target = "valueNet", ignore = true)
    public abstract EstimateLineEntity toCreateDaoModel(EstimateLineServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "estimate", expression = "java(estimateRef(source.getEstimateId()))")
    @Mapping(target = "workItem", expression = "java(workItemRef(source.getWorkItemId()))")
    @Mapping(target = "workPrice", expression = "java(workPriceRef(source.getWorkPriceId()))")
    @Mapping(target = "unit", expression = "java(unitRef(source.getUnitId()))")
    @Mapping(target = "quantity", ignore = true)
    @Mapping(target = "valueNet", ignore = true)
    public abstract void updateFields(EstimateLineServiceExtendedModel source, @MappingTarget EstimateLineEntity target);

    @Override
    @Mapping(target = "estimateId", source = "estimate.id")
    @Mapping(target = "workItemId", source = "workItem.id")
    @Mapping(target = "workItemName", ignore = true)
    @Mapping(target = "workPriceId", source = "workPrice.id")
    @Mapping(target = "unitId", source = "unit.id")
    @Mapping(target = "unitName", ignore = true)
    public abstract EstimateLineServiceModel toServiceModel(EstimateLineEntity source);

    @Override
    @Mapping(target = "estimateId", source = "estimate.id")
    @Mapping(target = "workItemId", source = "workItem.id")
    @Mapping(target = "workPriceId", source = "workPrice.id")
    @Mapping(target = "unitId", source = "unit.id")
    public abstract EstimateLineServiceExtendedModel toServiceExtendedModel(EstimateLineEntity source);

    /**
     * Resolves the localized work item name and unit name using the per-request locale rule (RU
     * when the request locale language is {@code ru}, PL otherwise).
     */
    @AfterMapping
    protected void resolveReferencedNames(@MappingTarget EstimateLineServiceModel target, EstimateLineEntity source) {
        WorkItemEntity workItem = source.getWorkItem();
        if (workItem != null) {
            target.setWorkItemName(isRussianLocale() ? workItem.getNameRU() : workItem.getNamePL());
        }
        MeasurementUnitEntity unit = source.getUnit();
        if (unit != null) {
            target.setUnitName(isRussianLocale() ? unit.getNameRU() : unit.getNamePL());
        }
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
