package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkVolumeFormulaEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.WorkVolumeFormulaServiceExtendedModel;
import com.foremen.service.model.WorkVolumeFormulaServiceModel;
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
 * Service mapper for the {@link WorkVolumeFormulaEntity} row (FOR-05-04, Requirement 2).
 *
 * <p>An <b>abstract class</b> (not an interface) so it can hold an injected {@link EntityManager}
 * used to turn the flat {@code workItemId} into a managed reference via {@code getReference(...)}
 * (no SELECT), mirroring {@code WorkPriceServiceMapper}.
 *
 * <p>Reads. {@code toServiceModel} maps {@code workItemId}/{@code sourceText} directly; the
 * referenced work item's localized display name ({@code workItemName}) is resolved in
 * {@link #resolveReferencedNames} using the request-locale rule (RU when the request locale
 * language is {@code ru}, PL otherwise).
 *
 * <p>Writes. {@code toCreateDaoModel}/{@code updateFields} set the {@code workItem} reference via
 * an {@code expression} mapping and {@code sourceText} directly from the write model.
 * {@code parsedAst} is <b>not</b> mapped here — it MUST be derived server-side from
 * {@code sourceText} by running {@code FormulaParser.parse(...)} then
 * {@code FormulaValidator.validate(...)} before persist (Requirement 2.6). Wiring that
 * parse+validate step into the create/update service flow is <b>task 18.3's scope</b>
 * (controller/service wiring), not this DTO/mapper-layer task — this mapper only ignores
 * {@code parsedAst} on the write side (mirroring {@code WorkPriceServiceMapper}'s
 * derived-field-ignored-on-inbound convention) so the entity is left without a valid AST until
 * the service layer sets it.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class WorkVolumeFormulaServiceMapper
        implements ServiceToDaoMapper<WorkVolumeFormulaEntity, WorkVolumeFormulaServiceModel,
        WorkVolumeFormulaServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected WorkItemEntity workItemRef(Long id) {
        return id == null ? null : entityManager.getReference(WorkItemEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workItem", expression = "java(workItemRef(source.workItemId()))")
    @Mapping(target = "sourceText", source = "sourceText")
    // parsedAst is derived server-side (task 18.3), not client-settable.
    @Mapping(target = "parsedAst", ignore = true)
    public abstract WorkVolumeFormulaEntity toCreateDaoModel(WorkVolumeFormulaServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workItem", expression = "java(workItemRef(source.workItemId()))")
    @Mapping(target = "sourceText", source = "sourceText")
    // parsedAst is derived server-side (task 18.3), not client-settable.
    @Mapping(target = "parsedAst", ignore = true)
    public abstract void updateFields(WorkVolumeFormulaServiceExtendedModel source,
                                      @MappingTarget WorkVolumeFormulaEntity target);

    @Override
    @Mapping(target = "workItemId", source = "workItem.id")
    @Mapping(target = "workItemName", ignore = true)
    @Mapping(target = "sourceText", source = "sourceText")
    public abstract WorkVolumeFormulaServiceModel toServiceModel(WorkVolumeFormulaEntity source);

    @Override
    @Mapping(target = "workItemId", source = "workItem.id")
    @Mapping(target = "sourceText", source = "sourceText")
    public abstract WorkVolumeFormulaServiceExtendedModel toServiceExtendedModel(WorkVolumeFormulaEntity source);

    /**
     * Resolves the referenced work item's localized display name into {@code workItemName} using
     * the same per-request locale rule the framework uses for {@code name} (RU when the request
     * locale language is {@code ru}, PL otherwise).
     */
    @AfterMapping
    protected void resolveReferencedNames(@MappingTarget WorkVolumeFormulaServiceModel target,
                                          WorkVolumeFormulaEntity source) {
        WorkItemEntity item = source.getWorkItem();
        if (item != null) {
            target.setWorkItemName(isRussianLocale() ? item.getNameRU() : item.getNamePL());
        }
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
