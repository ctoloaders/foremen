package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.WorkItemServiceExtendedModel;
import com.foremen.service.model.WorkItemServiceModel;
import jakarta.persistence.EntityManager;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;
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

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
