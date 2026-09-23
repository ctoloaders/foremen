package com.foremen.service.model.mapper;

import java.util.Locale;
import java.util.Set;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.AssortmentGroupEntity;
import com.foremen.dao.model.AssortmentPositionEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.AssortmentPositionServiceExtendedModel;
import com.foremen.service.model.AssortmentPositionServiceModel;

import jakarta.persistence.EntityManager;

/**
 * Service mapper for {@link AssortmentPositionEntity} (FOR-05-04-UI assortment rework).
 *
 * <p>An <b>abstract class</b> (not an interface) so it can hold an injected {@link EntityManager}
 * used to turn the flat {@code assortmentGroupId}/{@code materialTypeId} into managed references
 * via {@code getReference(...)} (no SELECT), mirroring {@code AssortmentLineItemServiceMapper} and
 * {@code WorkMaterialConsumptionServiceMapper}.
 *
 * <p>Reads. {@code toServiceModel} maps the flat FK ids and, in {@link #resolveReferencedNames},
 * localizes the referenced group's/material type's display names using the request-locale rule
 * (RU when the request locale language is {@code ru}, PL otherwise).
 *
 * <p>Writes. {@code toCreateDaoModel}/{@code updateFields} set the {@code group}/
 * {@code materialType} references via {@code expression} mappings and copy {@code sortOrder}
 * straight through.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class AssortmentPositionServiceMapper
        implements ServiceToDaoMapper<AssortmentPositionEntity, AssortmentPositionServiceModel,
        AssortmentPositionServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected AssortmentGroupEntity assortmentGroupRef(Long id) {
        return id == null ? null : entityManager.getReference(AssortmentGroupEntity.class, id);
    }

    protected MaterialTypeEntity materialTypeRef(Long id) {
        return id == null ? null : entityManager.getReference(MaterialTypeEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "group", expression = "java(assortmentGroupRef(source.assortmentGroupId()))")
    @Mapping(target = "materialType", expression = "java(materialTypeRef(source.materialTypeId()))")
    @Mapping(target = "sortOrder", source = "sortOrder")
    public abstract AssortmentPositionEntity toCreateDaoModel(AssortmentPositionServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "group", expression = "java(assortmentGroupRef(source.assortmentGroupId()))")
    @Mapping(target = "materialType", expression = "java(materialTypeRef(source.materialTypeId()))")
    @Mapping(target = "sortOrder", source = "sortOrder")
    public abstract void updateFields(AssortmentPositionServiceExtendedModel source,
                                      @MappingTarget AssortmentPositionEntity target);

    @Override
    @Mapping(target = "assortmentGroupId", source = "group.id")
    @Mapping(target = "assortmentGroupName", ignore = true)
    @Mapping(target = "materialTypeId", source = "materialType.id")
    @Mapping(target = "materialTypeName", ignore = true)
    @Mapping(target = "sortOrder", source = "sortOrder")
    public abstract AssortmentPositionServiceModel toServiceModel(AssortmentPositionEntity source);

    @Override
    @Mapping(target = "assortmentGroupId", source = "group.id")
    @Mapping(target = "materialTypeId", source = "materialType.id")
    @Mapping(target = "sortOrder", source = "sortOrder")
    public abstract AssortmentPositionServiceExtendedModel toServiceExtendedModel(AssortmentPositionEntity source);

    /**
     * Resolves the referenced assortment group's and material type's localized display names using
     * the per-request locale rule (RU when the request locale language is {@code ru}, PL
     * otherwise).
     */
    @AfterMapping
    protected void resolveReferencedNames(@MappingTarget AssortmentPositionServiceModel target,
                                          AssortmentPositionEntity source) {
        boolean ru = isRussianLocale();
        AssortmentGroupEntity group = source.getGroup();
        if (group != null) {
            target.setAssortmentGroupName(ru ? group.getNameRU() : group.getNamePL());
        }
        MaterialTypeEntity materialType = source.getMaterialType();
        if (materialType != null) {
            target.setMaterialTypeName(ru ? materialType.getNameRU() : materialType.getNamePL());
        }
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
