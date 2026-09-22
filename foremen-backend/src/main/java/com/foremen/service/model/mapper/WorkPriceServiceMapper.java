package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.model.WorkPriceServiceModel;
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
 * Service mapper for the {@link WorkPriceEntity} single-price row (FOR-05-04, Requirement 1).
 *
 * <p>An <b>abstract class</b> (not an interface) so it can hold injected collaborators: an
 * {@link EntityManager} (to turn flat ids into managed references via {@code getReference(...)} without
 * a SELECT).
 *
 * <p>Reads. {@code toServiceModel} maps {@code workItemId} directly plus the row's own
 * {@code currency}/{@code netPrice}; the referenced work item's localized display name
 * ({@code workItemName}) is resolved in {@link #resolveReferencedNames} using the request-locale rule
 * (RU when the request locale language is {@code ru}, PL otherwise).
 *
 * <p>Writes. {@code toCreateDaoModel} / {@code updateFields} set the {@code workItem}/{@code currency}
 * references via {@code expression} mappings and {@code netPrice} directly from the write model — no
 * MAX-fallback, no per-package resolution (Requirement 1.3).
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class WorkPriceServiceMapper
        implements ServiceToDaoMapper<WorkPriceEntity, WorkPriceServiceModel, WorkPriceServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected WorkItemEntity workItemRef(Long id) {
        return id == null ? null : entityManager.getReference(WorkItemEntity.class, id);
    }

    protected CurrencyEntity currencyRef(Long id) {
        return id == null ? null : entityManager.getReference(CurrencyEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workItem", expression = "java(workItemRef(source.workItemId()))")
    @Mapping(target = "currency", expression = "java(currencyRef(source.currencyId()))")
    @Mapping(target = "netPrice", source = "netPrice")
    public abstract WorkPriceEntity toCreateDaoModel(WorkPriceServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workItem", expression = "java(workItemRef(source.workItemId()))")
    @Mapping(target = "currency", expression = "java(currencyRef(source.currencyId()))")
    @Mapping(target = "netPrice", source = "netPrice")
    public abstract void updateFields(WorkPriceServiceExtendedModel source, @MappingTarget WorkPriceEntity target);

    @Override
    @Mapping(target = "workItemId", source = "workItem.id")
    @Mapping(target = "currencyId", source = "currency.id")
    @Mapping(target = "currencyCode", source = "currency.code")
    @Mapping(target = "netPrice", source = "netPrice")
    public abstract WorkPriceServiceModel toServiceModel(WorkPriceEntity source);

    @Override
    @Mapping(target = "workItemId", source = "workItem.id")
    @Mapping(target = "currencyId", source = "currency.id")
    @Mapping(target = "netPrice", source = "netPrice")
    public abstract WorkPriceServiceExtendedModel toServiceExtendedModel(WorkPriceEntity source);

    /**
     * Resolves the referenced work item's localized display name into {@code workItemName} using the
     * same per-request locale rule the framework uses for {@code name} (RU when the request locale
     * language is {@code ru}, PL otherwise).
     */
    @AfterMapping
    protected void resolveReferencedNames(@MappingTarget WorkPriceServiceModel target, WorkPriceEntity source) {
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
