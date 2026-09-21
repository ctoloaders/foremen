package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.EstimateServiceExtendedModel;
import com.foremen.service.model.EstimateServiceModel;
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
 * Service mapper for {@link EstimateEntity} (FOR-05-03, Requirement 1), following the FOR-04
 * {@code RoomServiceMapper} FK-resolution pattern: an <b>abstract class</b> holding an injected
 * {@link EntityManager} so the flat {@code projectId}/{@code currencyId}/{@code vatRateId} become
 * managed references via {@code getReference(...)} without a SELECT.
 *
 * <p>Estimate has no own i18n {@code name}, so {@link #getI18nSupportedProperties()} returns an
 * empty set. The read model resolves {@code projectName}/{@code currencyCode} (plain) and the
 * localized {@code vatRateName} in an {@code @AfterMapping}, mirroring {@code RoomServiceMapper}.
 *
 * <p><b>Derived columns are ignored inbound (R8.4, task 5.2):</b> {@code totalNet}, {@code totalVat},
 * and {@code totalGross} are computed exclusively by {@code EstimateRecomputeService}; a client
 * payload can set them on the write model, but {@link #toCreateDaoModel} and {@link #updateFields}
 * never copy them onto the entity, so they have no effect.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class EstimateServiceMapper
        implements ServiceToDaoMapper<EstimateEntity, EstimateServiceModel, EstimateServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected ProjectEntity projectRef(Long id) {
        return id == null ? null : entityManager.getReference(ProjectEntity.class, id);
    }

    protected CurrencyEntity currencyRef(Long id) {
        return id == null ? null : entityManager.getReference(CurrencyEntity.class, id);
    }

    protected VatRateEntity vatRateRef(Long id) {
        return id == null ? null : entityManager.getReference(VatRateEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", expression = "java(projectRef(source.getProjectId()))")
    @Mapping(target = "currency", expression = "java(currencyRef(source.getCurrencyId()))")
    @Mapping(target = "vatRate", expression = "java(vatRateRef(source.getVatRateId()))")
    @Mapping(target = "totalNet", ignore = true)
    @Mapping(target = "totalVat", ignore = true)
    @Mapping(target = "totalGross", ignore = true)
    public abstract EstimateEntity toCreateDaoModel(EstimateServiceExtendedModel source);

    /**
     * Update-path mapping: {@code project} is deliberately left untouched (no {@code @Mapping} for
     * it at all, not merely a no-op {@code ignore = true} on an expression). {@code
     * EstimateUpdateRequest}/{@code EstimateControllerMapper#toUpdateServiceExtendedModel} never
     * populate {@code projectId} (the owning project is fixed at create time, R1.1, R1.6), so
     * re-deriving {@code project} via {@code projectRef(source.getProjectId())} here would resolve
     * to {@code projectRef(null) == null} and null out the existing FK on every update — the bug
     * this mapping avoids by simply not being present.
     */
    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "currency", expression = "java(currencyRef(source.getCurrencyId()))")
    @Mapping(target = "vatRate", expression = "java(vatRateRef(source.getVatRateId()))")
    @Mapping(target = "totalNet", ignore = true)
    @Mapping(target = "totalVat", ignore = true)
    @Mapping(target = "totalGross", ignore = true)
    public abstract void updateFields(EstimateServiceExtendedModel source, @MappingTarget EstimateEntity target);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "projectName", ignore = true)
    @Mapping(target = "currencyId", source = "currency.id")
    @Mapping(target = "currencyCode", ignore = true)
    @Mapping(target = "vatRateId", source = "vatRate.id")
    @Mapping(target = "vatRateName", ignore = true)
    public abstract EstimateServiceModel toServiceModel(EstimateEntity source);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "currencyId", source = "currency.id")
    @Mapping(target = "vatRateId", source = "vatRate.id")
    public abstract EstimateServiceExtendedModel toServiceExtendedModel(EstimateEntity source);

    /**
     * Resolves the referenced project name, currency code, and localized VAT rate name, using the
     * per-request locale rule (RU when the request locale language is {@code ru}, PL otherwise).
     */
    @AfterMapping
    protected void resolveReferencedNames(@MappingTarget EstimateServiceModel target, EstimateEntity source) {
        ProjectEntity project = source.getProject();
        if (project != null) {
            target.setProjectName(project.getName());
        }
        CurrencyEntity currency = source.getCurrency();
        if (currency != null) {
            target.setCurrencyCode(currency.getCode());
        }
        VatRateEntity vatRate = source.getVatRate();
        if (vatRate != null) {
            target.setVatRateName(isRussianLocale() ? vatRate.getNameRU() : vatRate.getNamePL());
        }
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
