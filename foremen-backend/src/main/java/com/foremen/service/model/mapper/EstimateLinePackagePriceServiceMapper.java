package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLinePackagePriceEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkPackagePriceEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.EstimateLinePackagePriceServiceExtendedModel;
import com.foremen.service.model.EstimateLinePackagePriceServiceModel;
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
 * Service mapper for {@link EstimateLinePackagePriceEntity} (FOR-05-03, Requirements 4, 5),
 * following the FOR-04 {@code RoomServiceMapper} FK-resolution pattern: an <b>abstract class</b>
 * holding an injected {@link EntityManager} so the flat {@code lineId}/{@code offerPackageId}/
 * {@code workPackagePriceId} become managed references via {@code getReference(...)} without a
 * SELECT.
 *
 * <p>EstimateLinePackagePrice has no own i18n {@code name}, so {@link #getI18nSupportedProperties()}
 * returns an empty set. The read model resolves the localized {@code offerPackageName} in an
 * {@code @AfterMapping}.
 *
 * <p><b>Derived/snapshot columns are ignored inbound (R4.3, R4.7, R5.2, R5.3, task 5.2):</b>
 * {@code originalUnitPrice} (copied at add-time by {@code PackagePriceSnapshotService}), the
 * effective {@code unitPrice} (derived via {@code applyDiscount}), and {@code unpriced} (set by the
 * snapshot service) are never copied from the write model onto the entity by
 * {@link #toCreateDaoModel}/{@link #updateFields} — a client payload setting them has no effect. The
 * client-owned discount placeholder fields ({@code discountKind}, {@code discountValue}) and the
 * {@code workPackagePrice} provenance FK map through normally.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class EstimateLinePackagePriceServiceMapper
        implements ServiceToDaoMapper<EstimateLinePackagePriceEntity, EstimateLinePackagePriceServiceModel,
        EstimateLinePackagePriceServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected EstimateLineEntity lineRef(Long id) {
        return id == null ? null : entityManager.getReference(EstimateLineEntity.class, id);
    }

    protected OfferPackageEntity offerPackageRef(Long id) {
        return id == null ? null : entityManager.getReference(OfferPackageEntity.class, id);
    }

    protected WorkPackagePriceEntity workPackagePriceRef(Long id) {
        return id == null ? null : entityManager.getReference(WorkPackagePriceEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Collections.emptySet();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "line", expression = "java(lineRef(source.getLineId()))")
    @Mapping(target = "offerPackage", expression = "java(offerPackageRef(source.getOfferPackageId()))")
    @Mapping(target = "workPackagePrice", expression = "java(workPackagePriceRef(source.getWorkPackagePriceId()))")
    @Mapping(target = "originalUnitPrice", ignore = true)
    @Mapping(target = "unitPrice", ignore = true)
    @Mapping(target = "unpriced", ignore = true)
    public abstract EstimateLinePackagePriceEntity toCreateDaoModel(EstimateLinePackagePriceServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "line", expression = "java(lineRef(source.getLineId()))")
    @Mapping(target = "offerPackage", expression = "java(offerPackageRef(source.getOfferPackageId()))")
    @Mapping(target = "workPackagePrice", expression = "java(workPackagePriceRef(source.getWorkPackagePriceId()))")
    @Mapping(target = "originalUnitPrice", ignore = true)
    @Mapping(target = "unitPrice", ignore = true)
    @Mapping(target = "unpriced", ignore = true)
    public abstract void updateFields(
            EstimateLinePackagePriceServiceExtendedModel source, @MappingTarget EstimateLinePackagePriceEntity target);

    @Override
    @Mapping(target = "lineId", source = "line.id")
    @Mapping(target = "offerPackageId", source = "offerPackage.id")
    @Mapping(target = "offerPackageName", ignore = true)
    @Mapping(target = "workPackagePriceId", source = "workPackagePrice.id")
    public abstract EstimateLinePackagePriceServiceModel toServiceModel(EstimateLinePackagePriceEntity source);

    @Override
    @Mapping(target = "lineId", source = "line.id")
    @Mapping(target = "offerPackageId", source = "offerPackage.id")
    @Mapping(target = "workPackagePriceId", source = "workPackagePrice.id")
    public abstract EstimateLinePackagePriceServiceExtendedModel toServiceExtendedModel(
            EstimateLinePackagePriceEntity source);

    /** Resolves the referenced offer package's localized display name into {@code offerPackageName}. */
    @AfterMapping
    protected void resolveReferencedNames(
            @MappingTarget EstimateLinePackagePriceServiceModel target, EstimateLinePackagePriceEntity source) {
        OfferPackageEntity offerPackage = source.getOfferPackage();
        if (offerPackage != null) {
            target.setOfferPackageName(isRussianLocale() ? offerPackage.getNameRU() : offerPackage.getNamePL());
        }
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
