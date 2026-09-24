package com.foremen.service.model.mapper;

import java.util.Collections;
import java.util.Set;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.EstimateLineRoomMaterialServiceExtendedModel;
import com.foremen.service.model.EstimateLineRoomMaterialServiceModel;

import jakarta.persistence.EntityManager;

/**
 * Service mapper for {@link EstimateLineRoomMaterialEntity} (FOR-05-05 design §B1), following the
 * FOR-05-03 {@code EstimateLineRoomQtyServiceMapper} FK-resolution pattern: an <b>abstract class</b>
 * holding an injected {@link EntityManager} so the flat reference ids become managed references via
 * {@code getReference(...)} without a SELECT.
 *
 * <p>A material line has no own i18n {@code name}, so {@link #getI18nSupportedProperties()} returns
 * an empty set. This mapper exists to satisfy the {@link ServiceToDaoMapper} contract required by the
 * {@code EstimateAssignmentService} {@link com.foremen.service.ProjectScopedService} plumbing; the
 * orchestrator's bespoke {@code assign}/{@code unassign}/{@code applyAssignments} methods mutate the
 * entity graph directly (copying the frozen price snapshot in code, not through the mapper).
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class EstimateLineRoomMaterialServiceMapper
        implements ServiceToDaoMapper<EstimateLineRoomMaterialEntity, EstimateLineRoomMaterialServiceModel,
        EstimateLineRoomMaterialServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected EstimateLineRoomQtyEntity roomQtyRef(Long id) {
        return id == null ? null : entityManager.getReference(EstimateLineRoomQtyEntity.class, id);
    }

    protected ConstructionMaterialTypeEntity constructionTypeRef(Long id) {
        return id == null ? null : entityManager.getReference(ConstructionMaterialTypeEntity.class, id);
    }

    protected MaterialTypeEntity finishingTypeRef(Long id) {
        return id == null ? null : entityManager.getReference(MaterialTypeEntity.class, id);
    }

    protected ConstructionMaterialEntity constructionMaterialRef(Long id) {
        return id == null ? null : entityManager.getReference(ConstructionMaterialEntity.class, id);
    }

    protected FinishingMaterialEntity finishingMaterialRef(Long id) {
        return id == null ? null : entityManager.getReference(FinishingMaterialEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "roomQty", expression = "java(roomQtyRef(source.getRoomQtyId()))")
    @Mapping(target = "constructionType", expression = "java(constructionTypeRef(source.getConstructionTypeId()))")
    @Mapping(target = "finishingType", expression = "java(finishingTypeRef(source.getFinishingTypeId()))")
    @Mapping(target = "sourceConstructionMaterial",
            expression = "java(constructionMaterialRef(source.getSourceConstructionMaterialId()))")
    @Mapping(target = "sourceFinishingMaterial",
            expression = "java(finishingMaterialRef(source.getSourceFinishingMaterialId()))")
    @Mapping(target = "concreteConstructionMaterial",
            expression = "java(constructionMaterialRef(source.getConcreteConstructionMaterialId()))")
    @Mapping(target = "concreteFinishingMaterial",
            expression = "java(finishingMaterialRef(source.getConcreteFinishingMaterialId()))")
    public abstract EstimateLineRoomMaterialEntity toCreateDaoModel(
            EstimateLineRoomMaterialServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "roomQty", expression = "java(roomQtyRef(source.getRoomQtyId()))")
    @Mapping(target = "constructionType", expression = "java(constructionTypeRef(source.getConstructionTypeId()))")
    @Mapping(target = "finishingType", expression = "java(finishingTypeRef(source.getFinishingTypeId()))")
    @Mapping(target = "sourceConstructionMaterial",
            expression = "java(constructionMaterialRef(source.getSourceConstructionMaterialId()))")
    @Mapping(target = "sourceFinishingMaterial",
            expression = "java(finishingMaterialRef(source.getSourceFinishingMaterialId()))")
    @Mapping(target = "concreteConstructionMaterial",
            expression = "java(constructionMaterialRef(source.getConcreteConstructionMaterialId()))")
    @Mapping(target = "concreteFinishingMaterial",
            expression = "java(finishingMaterialRef(source.getConcreteFinishingMaterialId()))")
    public abstract void updateFields(
            EstimateLineRoomMaterialServiceExtendedModel source, @MappingTarget EstimateLineRoomMaterialEntity target);

    @Override
    @Mapping(target = "roomQtyId", source = "roomQty.id")
    @Mapping(target = "constructionTypeId", source = "constructionType.id")
    @Mapping(target = "finishingTypeId", source = "finishingType.id")
    @Mapping(target = "sourceConstructionMaterialId", source = "sourceConstructionMaterial.id")
    @Mapping(target = "sourceFinishingMaterialId", source = "sourceFinishingMaterial.id")
    @Mapping(target = "concreteConstructionMaterialId", source = "concreteConstructionMaterial.id")
    @Mapping(target = "concreteFinishingMaterialId", source = "concreteFinishingMaterial.id")
    public abstract EstimateLineRoomMaterialServiceModel toServiceModel(EstimateLineRoomMaterialEntity source);

    @Override
    @Mapping(target = "roomQtyId", source = "roomQty.id")
    @Mapping(target = "constructionTypeId", source = "constructionType.id")
    @Mapping(target = "finishingTypeId", source = "finishingType.id")
    @Mapping(target = "sourceConstructionMaterialId", source = "sourceConstructionMaterial.id")
    @Mapping(target = "sourceFinishingMaterialId", source = "sourceFinishingMaterial.id")
    @Mapping(target = "concreteConstructionMaterialId", source = "concreteConstructionMaterial.id")
    @Mapping(target = "concreteFinishingMaterialId", source = "concreteFinishingMaterial.id")
    public abstract EstimateLineRoomMaterialServiceExtendedModel toServiceExtendedModel(
            EstimateLineRoomMaterialEntity source);
}
