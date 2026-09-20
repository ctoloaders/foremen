package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.controller.model.MoneyRangeDto;
import com.foremen.controller.model.WorkCatalogPackageCellDto;
import com.foremen.service.model.WorkItemServiceExtendedModel;
import com.foremen.service.model.WorkItemServiceModel;
import com.foremen.service.pricing.MaterialRangeResolver.MoneyRange;
import com.foremen.service.pricing.WorkCatalogAggregationResolver;
import com.foremen.service.pricing.WorkCatalogAggregationResolver.PackageCell;
import jakarta.persistence.EntityManager;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Service mapper for {@link WorkItemEntity}.
 *
 * <p>This is the first mapper that resolves flat FK ids into JPA associations. It is declared as an
 * <b>abstract class</b> (not an interface) so it can hold an injected {@link EntityManager} and use
 * {@code getReference(...)} to turn {@code workCategoryId}/{@code unitId} into managed references
 * without triggering a SELECT. The write mappings ({@link #toCreateDaoModel} / {@link #updateFields})
 * use {@code expression} mappings that call the {@link #workCategoryRef}/{@link #unitRef} helpers.
 *
 * <p>Read mappings map {@code workCategory.id -> workCategoryId} and {@code unit.id -> unitId}. The
 * i18n {@code name} collapse is handled by the parent {@code I18nPropertiesMapper} {@code @AfterMapping}.
 * The referenced display names ({@code workCategoryName}/{@code unitName}) are resolved in a local
 * {@code @AfterMapping} using the same request-locale rule the framework applies to {@code name}
 * (RU for {@code ru}, PL otherwise — PL fallback).
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class WorkItemServiceMapper
        implements ServiceToDaoMapper<WorkItemEntity, WorkItemServiceModel, WorkItemServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    @Autowired
    protected WorkCatalogAggregationResolver workCatalogAggregationResolver;

    protected WorkCategoryEntity workCategoryRef(Long id) {
        return id == null ? null : entityManager.getReference(WorkCategoryEntity.class, id);
    }

    protected MeasurementUnitEntity unitRef(Long id) {
        return id == null ? null : entityManager.getReference(MeasurementUnitEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workCategory", expression = "java(workCategoryRef(source.workCategoryId()))")
    @Mapping(target = "unit", expression = "java(unitRef(source.unitId()))")
    public abstract WorkItemEntity toCreateDaoModel(WorkItemServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workCategory", expression = "java(workCategoryRef(source.workCategoryId()))")
    @Mapping(target = "unit", expression = "java(unitRef(source.unitId()))")
    public abstract void updateFields(WorkItemServiceExtendedModel source, @MappingTarget WorkItemEntity target);

    @Override
    @Mapping(target = "workCategoryId", source = "workCategory.id")
    @Mapping(target = "unitId", source = "unit.id")
    public abstract WorkItemServiceModel toServiceModel(WorkItemEntity source);

    @Override
    @Mapping(target = "workCategoryId", source = "workCategory.id")
    @Mapping(target = "unitId", source = "unit.id")
    public abstract WorkItemServiceExtendedModel toServiceExtendedModel(WorkItemEntity source);

    /**
     * Resolves the referenced entities' localized display names into {@code workCategoryName} /
     * {@code unitName} using the same per-request locale rule the framework uses for {@code name}
     * (RU when the request locale language is {@code ru}, PL otherwise).
     */
    @AfterMapping
    protected void resolveReferencedNames(@MappingTarget WorkItemServiceModel target, WorkItemEntity source) {
        boolean russian = isRussianLocale();
        WorkCategoryEntity category = source.getWorkCategory();
        if (category != null) {
            target.setWorkCategoryName(russian ? category.getNameRU() : category.getNamePL());
        }
        MeasurementUnitEntity unit = source.getUnit();
        if (unit != null) {
            target.setUnitName(russian ? unit.getNameRU() : unit.getNamePL());
        }
    }

    /**
     * Attaches the FOR-04-19 work-catalog pivot ({@code packagePivot}, keyed by {@code offerPackage.id})
     * onto the read model, exactly as the FOR-04-12b {@code WorkPrice} mapper attaches its {@code prices}
     * map in an {@code @AfterMapping}. Each cell carries the THREE prices for the {@code (work, package)}
     * — the labour price plus the construction and finishing material money ranges — resolved by
     * {@link WorkCatalogAggregationResolver}.
     *
     * <p>The map is attached onto the existing row, so the list read stays paginated over DISTINCT
     * {@code WorkItem} rows and the synthetic pivot fields never multiply or split rows (Requirement
     * 5.1, 5.2, 5.3). The aggregation resolver batches by work-item id (here a batch of the single row),
     * loading consumption rows, work prices, and analog batches with grouped {@code IN} queries.
     */
    @AfterMapping
    protected void resolvePackagePivot(@MappingTarget WorkItemServiceModel target, WorkItemEntity source) {
        Long workItemId = source.getId();
        if (workItemId == null) {
            return;
        }
        Map<Long, Map<Long, PackageCell>> pivot =
                workCatalogAggregationResolver.resolve(List.of(workItemId));
        Map<Long, PackageCell> cells = pivot.get(workItemId);
        if (cells == null || cells.isEmpty()) {
            return;
        }
        Map<Long, WorkCatalogPackageCellDto> dto = new LinkedHashMap<>();
        for (Map.Entry<Long, PackageCell> entry : cells.entrySet()) {
            dto.put(entry.getKey(), toPackageCellDto(entry.getValue()));
        }
        target.setPackagePivot(dto);
    }

    /**
     * Decouples the internal {@link PackageCell} (with its {@code MoneyRange} bands) into the
     * controller-model {@link WorkCatalogPackageCellDto} (with {@link MoneyRangeDto} bands), so the read
     * model carries no pricing-internal type — mirroring how the FOR-04-19 consumption mapper surfaces
     * its {@code typeBatchRange} as a {@code MoneyRangeDto}.
     */
    private static WorkCatalogPackageCellDto toPackageCellDto(PackageCell cell) {
        if (cell == null) {
            return null;
        }
        return new WorkCatalogPackageCellDto(
                cell.offerPackageId(),
                cell.labourPrice(),
                toMoneyRangeDto(cell.construction()),
                toMoneyRangeDto(cell.finishing()));
    }

    private static MoneyRangeDto toMoneyRangeDto(MoneyRange range) {
        return range == null ? null : new MoneyRangeDto(range.min(), range.max());
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
