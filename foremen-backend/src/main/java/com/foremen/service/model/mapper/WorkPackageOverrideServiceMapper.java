package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPackageOverrideEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.WorkPackageOverrideServiceExtendedModel;
import com.foremen.service.model.WorkPackageOverrideServiceModel;
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
 * Service mapper for the {@link WorkPackageOverrideEntity} row (FOR-05-04, Requirement 4).
 *
 * <p>An <b>abstract class</b> (not an interface) so it can hold an injected {@link EntityManager}
 * used to turn the flat {@code workItemId}/{@code offerPackageId} into managed references via
 * {@code getReference(...)} (no SELECT), mirroring {@code WorkPriceServiceMapper}.
 *
 * <p>Reads. {@code toServiceModel} maps {@code workItemId}/{@code offerPackageId}/{@code member}/
 * {@code overrideSourceText} directly; the referenced work item's and offer package's localized
 * display names ({@code workItemName}/{@code offerPackageName}) are resolved in
 * {@link #resolveReferencedNames} using the request-locale rule (RU when the request locale
 * language is {@code ru}, PL otherwise).
 *
 * <p>Writes. {@code toCreateDaoModel}/{@code updateFields} set the {@code workItem}/
 * {@code offerPackage} references via {@code expression} mappings, {@code member} and
 * {@code overrideSourceText} directly from the write model, and carry <b>no price</b>
 * (Requirement 4.5). {@code overrideParsedAst} is <b>not</b> mapped here — it MUST be derived
 * server-side from {@code overrideSourceText} by running {@code FormulaParser.parse(...)} then
 * {@code FormulaValidator.validate(...)} before persist (Requirement 4.4). Wiring that
 * parse+validate step into the create/update service flow is <b>task 18.3's scope</b>
 * (controller/service wiring), not this DTO/mapper-layer task — this mapper only ignores
 * {@code overrideParsedAst} on the write side (mirroring {@code WorkPriceServiceMapper}'s
 * derived-field-ignored-on-inbound convention).
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class WorkPackageOverrideServiceMapper
        implements ServiceToDaoMapper<WorkPackageOverrideEntity, WorkPackageOverrideServiceModel,
        WorkPackageOverrideServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected WorkItemEntity workItemRef(Long id) {
        return id == null ? null : entityManager.getReference(WorkItemEntity.class, id);
    }

    protected OfferPackageEntity offerPackageRef(Long id) {
        return id == null ? null : entityManager.getReference(OfferPackageEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workItem", expression = "java(workItemRef(source.workItemId()))")
    @Mapping(target = "offerPackage", expression = "java(offerPackageRef(source.offerPackageId()))")
    @Mapping(target = "member", source = "member")
    @Mapping(target = "overrideSourceText", source = "overrideSourceText")
    // overrideParsedAst is derived server-side (task 18.3), not client-settable.
    @Mapping(target = "overrideParsedAst", ignore = true)
    public abstract WorkPackageOverrideEntity toCreateDaoModel(WorkPackageOverrideServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workItem", expression = "java(workItemRef(source.workItemId()))")
    @Mapping(target = "offerPackage", expression = "java(offerPackageRef(source.offerPackageId()))")
    @Mapping(target = "member", source = "member")
    @Mapping(target = "overrideSourceText", source = "overrideSourceText")
    // overrideParsedAst is derived server-side (task 18.3), not client-settable.
    @Mapping(target = "overrideParsedAst", ignore = true)
    public abstract void updateFields(WorkPackageOverrideServiceExtendedModel source,
                                      @MappingTarget WorkPackageOverrideEntity target);

    @Override
    @Mapping(target = "workItemId", source = "workItem.id")
    @Mapping(target = "workItemName", ignore = true)
    @Mapping(target = "offerPackageId", source = "offerPackage.id")
    @Mapping(target = "offerPackageName", ignore = true)
    @Mapping(target = "member", source = "member")
    @Mapping(target = "overrideSourceText", source = "overrideSourceText")
    public abstract WorkPackageOverrideServiceModel toServiceModel(WorkPackageOverrideEntity source);

    @Override
    @Mapping(target = "workItemId", source = "workItem.id")
    @Mapping(target = "offerPackageId", source = "offerPackage.id")
    @Mapping(target = "member", source = "member")
    @Mapping(target = "overrideSourceText", source = "overrideSourceText")
    public abstract WorkPackageOverrideServiceExtendedModel toServiceExtendedModel(
            WorkPackageOverrideEntity source);

    /**
     * Resolves the referenced work item's and offer package's localized display names using the
     * same per-request locale rule the framework uses for {@code name} (RU when the request
     * locale language is {@code ru}, PL otherwise).
     */
    @AfterMapping
    protected void resolveReferencedNames(@MappingTarget WorkPackageOverrideServiceModel target,
                                          WorkPackageOverrideEntity source) {
        boolean ru = isRussianLocale();
        WorkItemEntity item = source.getWorkItem();
        if (item != null) {
            target.setWorkItemName(ru ? item.getNameRU() : item.getNamePL());
        }
        OfferPackageEntity pkg = source.getOfferPackage();
        if (pkg != null) {
            target.setOfferPackageName(ru ? pkg.getNameRU() : pkg.getNamePL());
        }
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
