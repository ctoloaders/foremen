package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.RefDto;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.WorkMaterialConsumptionServiceExtendedModel;
import com.foremen.service.model.WorkMaterialConsumptionServiceModel;
import com.foremen.service.pricing.WorkMaterialConsumptionEnrichmentResolver;
import com.foremen.service.pricing.WorkMaterialConsumptionEnrichmentResolver.Enrichment;
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
 * Service mapper for {@link WorkMaterialConsumptionEntity} (FOR-04-19, task 3.2).
 *
 * <p>An <b>abstract class</b> (following the FOR-04 {@code ConstructionMaterialServiceMapper}/
 * {@code FinishingMaterialServiceMapper} pattern) so it can hold the injected {@link EntityManager}
 * used to turn the flat write-model reference ids into managed {@code @ManyToOne} references via
 * {@code getReference(...)} (no SELECT). Reference <em>existence</em> is asserted by the
 * {@code WorkMaterialConsumptionService} normalize step before this mapper runs, so a dangling id is
 * rejected there rather than deferred to flush time.
 *
 * <p><b>Write path.</b> {@code toCreateDaoModel}/{@code updateFields} map {@code branch},
 * {@code normQty}/{@code wastePct}, the raw {@code justificationRU}/{@code justificationPL} pair and
 * the citation straight through (same field names), and turn the flat {@code workItemId}/
 * {@code materialUnitId}/{@code constructionMaterialTypeId}/
 * {@code finishingMaterialTypeId} into managed references via {@code getReference(...)}.
 *
 * <p><b>Read path.</b> {@code toServiceModel} leaves the {@link RefDto} references and the derived
 * {@code branchLabel} unset (resolved in {@link #resolveReadModel}) and copies {@code branch},
 * {@code normQty}/{@code wastePct}, the raw {@code justificationRU}/{@code justificationPL} and the
 * citation. The referenced rows are localized to {@link RefDto} objects ({@code id} + localized
 * {@code name}, RU when the request locale is {@code ru}, else PL — PL fallback). The single localized
 * {@code justification} (PL fallback: {@code ru}→{@code justificationRU}, else {@code justificationPL})
 * is populated by the shared i18n framework via {@link #getI18nSupportedProperties()} (Requirement
 * 3.8). The computed {@code typeBatchRange} + analog {@code materials} are stamped onto the read
 * model by the {@code WorkMaterialConsumptionEnrichmentResolver} in the {@code @AfterMapping} seams
 * (Requirement 5.4).
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class WorkMaterialConsumptionServiceMapper
        implements ServiceToDaoMapper<WorkMaterialConsumptionEntity, WorkMaterialConsumptionServiceModel,
        WorkMaterialConsumptionServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    @Autowired
    protected WorkMaterialConsumptionEnrichmentResolver enrichmentResolver;

    protected WorkItemEntity workItemRef(Long id) {
        return id == null ? null : entityManager.getReference(WorkItemEntity.class, id);
    }

    protected OfferPackageEntity offerPackageRef(Long id) {
        return id == null ? null : entityManager.getReference(OfferPackageEntity.class, id);
    }

    protected MeasurementUnitEntity materialUnitRef(Long id) {
        return id == null ? null : entityManager.getReference(MeasurementUnitEntity.class, id);
    }

    protected ConstructionMaterialTypeEntity constructionMaterialTypeRef(Long id) {
        return id == null ? null : entityManager.getReference(ConstructionMaterialTypeEntity.class, id);
    }

    protected MaterialTypeEntity finishingMaterialTypeRef(Long id) {
        return id == null ? null : entityManager.getReference(MaterialTypeEntity.class, id);
    }

    /**
     * The single localized {@code justification} (PL fallback) is the only i18n text owned by the
     * entity (Requirement 3.8). Returning {@code "justification"} lets the shared i18n framework
     * populate {@code justification} on the read model from {@code justificationRU}/
     * {@code justificationPL} per the request locale.
     */
    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of("justification");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workItem", expression = "java(workItemRef(source.getWorkItemId()))")
    @Mapping(target = "materialUnit", expression = "java(materialUnitRef(source.getMaterialUnitId()))")
    @Mapping(target = "constructionMaterialType",
            expression = "java(constructionMaterialTypeRef(source.getConstructionMaterialTypeId()))")
    @Mapping(target = "finishingMaterialType",
            expression = "java(finishingMaterialTypeRef(source.getFinishingMaterialTypeId()))")
    public abstract WorkMaterialConsumptionEntity toCreateDaoModel(WorkMaterialConsumptionServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workItem", expression = "java(workItemRef(source.getWorkItemId()))")
    @Mapping(target = "materialUnit", expression = "java(materialUnitRef(source.getMaterialUnitId()))")
    @Mapping(target = "constructionMaterialType",
            expression = "java(constructionMaterialTypeRef(source.getConstructionMaterialTypeId()))")
    @Mapping(target = "finishingMaterialType",
            expression = "java(finishingMaterialTypeRef(source.getFinishingMaterialTypeId()))")
    public abstract void updateFields(WorkMaterialConsumptionServiceExtendedModel source,
                                      @MappingTarget WorkMaterialConsumptionEntity target);

    @Override
    @Mapping(target = "workItem", ignore = true)
    @Mapping(target = "offerPackage", ignore = true)
    @Mapping(target = "materialUnit", ignore = true)
    @Mapping(target = "constructionMaterialType", ignore = true)
    @Mapping(target = "finishingMaterialType", ignore = true)
    @Mapping(target = "branchLabel", ignore = true)
    @Mapping(target = "typeBatchRange", ignore = true)
    @Mapping(target = "materials", ignore = true)
    @Mapping(target = "justification", ignore = true)
    public abstract WorkMaterialConsumptionServiceModel toServiceModel(WorkMaterialConsumptionEntity source);

    @Override
    @Mapping(target = "workItem", ignore = true)
    @Mapping(target = "offerPackage", ignore = true)
    @Mapping(target = "materialUnit", ignore = true)
    @Mapping(target = "constructionMaterialType", ignore = true)
    @Mapping(target = "finishingMaterialType", ignore = true)
    @Mapping(target = "branchLabel", ignore = true)
    @Mapping(target = "typeBatchRange", ignore = true)
    @Mapping(target = "materials", ignore = true)
    @Mapping(target = "justification", ignore = true)
    @Mapping(target = "workItemId", source = "workItem.id")
    @Mapping(target = "materialUnitId", source = "materialUnit.id")
    @Mapping(target = "constructionMaterialTypeId", source = "constructionMaterialType.id")
    @Mapping(target = "finishingMaterialTypeId", source = "finishingMaterialType.id")
    public abstract WorkMaterialConsumptionServiceExtendedModel toServiceExtendedModel(
            WorkMaterialConsumptionEntity source);

    /**
     * Resolves the read model's localized {@link RefDto} references and the derived
     * {@code branchLabel}. Each reference row is localized to {@code (id, name)} with the per-request
     * locale rule (RU when the request locale language is {@code ru}, else PL — PL fallback). The two
     * analog-group type references are localized independently; exactly one is non-null (the write
     * path enforces the XOR + branch-match rule), and the controller mapper picks the set one into
     * the DTO's single {@code materialType}. The computed {@code typeBatchRange}/{@code materials} are
     * populated later by the range resolver (tasks 4.x).
     */
    @AfterMapping
    protected void resolveReadModel(@MappingTarget WorkMaterialConsumptionServiceModel target,
                                    WorkMaterialConsumptionEntity source) {
        boolean ru = isRussianLocale();
        target.setWorkItem(workItemRef(source.getWorkItem(), ru));
        target.setMaterialUnit(ref(source.getMaterialUnit(), ru));
        target.setConstructionMaterialType(ref(source.getConstructionMaterialType(), ru));
        target.setFinishingMaterialType(ref(source.getFinishingMaterialType(), ru));
        Enrichment enrichment = enrichmentResolver.resolve(source);
        target.setTypeBatchRange(enrichment.typeBatchRange());
        target.setMaterials(enrichment.materials());
    }

    /**
     * Resolves the extended-read model's localized {@link RefDto} references, the derived
     * {@code branchLabel}, and the single localized {@code justification} (PL fallback). The extended
     * path is NOT covered by the shared i18n {@code processI18n} seam (which targets the read
     * {@code toServiceModel}), so the {@code justification} is resolved here to keep the edit-form
     * payload consistent with the list read.
     */
    @AfterMapping
    protected void resolveExtendedReadModel(@MappingTarget WorkMaterialConsumptionServiceExtendedModel target,
                                            WorkMaterialConsumptionEntity source) {
        boolean ru = isRussianLocale();
        target.setWorkItem(workItemRef(source.getWorkItem(), ru));
        target.setMaterialUnit(ref(source.getMaterialUnit(), ru));
        target.setConstructionMaterialType(ref(source.getConstructionMaterialType(), ru));
        target.setFinishingMaterialType(ref(source.getFinishingMaterialType(), ru));
        target.setJustification(ru ? source.getJustificationRU() : source.getJustificationPL());
        Enrichment enrichment = enrichmentResolver.resolve(source);
        target.setTypeBatchRange(enrichment.typeBatchRange());
        target.setMaterials(enrichment.materials());
    }

    private static RefDto workItemRef(WorkItemEntity e, boolean ru) {
        return e == null ? null : new RefDto(e.getId(), ru ? e.getNameRU() : e.getNamePL());
    }

    private static RefDto ref(OfferPackageEntity e, boolean ru) {
        return e == null ? null : new RefDto(e.getId(), ru ? e.getNameRU() : e.getNamePL());
    }

    private static RefDto ref(MeasurementUnitEntity e, boolean ru) {
        return e == null ? null : new RefDto(e.getId(), ru ? e.getNameRU() : e.getNamePL());
    }

    private static RefDto ref(ConstructionMaterialTypeEntity e, boolean ru) {
        return e == null ? null : new RefDto(e.getId(), ru ? e.getNameRU() : e.getNamePL());
    }

    private static RefDto ref(MaterialTypeEntity e, boolean ru) {
        return e == null ? null : new RefDto(e.getId(), ru ? e.getNameRU() : e.getNamePL());
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
