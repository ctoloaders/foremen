package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.RefDto;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.ConstructionMaterialServiceExtendedModel;
import com.foremen.service.model.ConstructionMaterialServiceModel;
import jakarta.persistence.EntityManager;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Service mapper for {@link ConstructionMaterialEntity} (FOR-04-17, task 5.2).
 *
 * <p>An <b>abstract class</b> (following the FOR-04 {@code WorkPriceServiceMapper}/
 * {@code RoomServiceMapper}/{@code MaterialProducerServiceMapper} pattern) so it can hold the
 * injected {@link EntityManager} (to turn the flat write-model reference ids into managed
 * references via {@code getReference(...)} without a SELECT) and the shared {@link ImageStorage}
 * seam (to resolve the read-time CDN {@code imageUrl} from the stored GCS object key —
 * Requirement 4.7, 8.4).
 *
 * <p><b>Write path.</b> {@code toCreateDaoModel}/{@code updateFields} map the localized
 * {@code nameRU}/{@code namePL}, the three prices, {@code website}, {@code image} object key and
 * {@code active} straight through (same field names), and turn the flat {@code typeId}/
 * {@code producerId}/{@code sellerId}/{@code unitId}/{@code currencyId} into managed
 * {@code @ManyToOne} references via {@code getReference(...)}. The {@code @ManyToMany packages} set
 * is (re)built in {@link #buildPackages} from {@code offerPackageIds} as managed
 * {@link OfferPackageEntity} references. Reference <em>existence</em> is asserted by the
 * {@code ConstructionMaterialService} normalize step before this mapper runs, so a dangling id is
 * rejected there rather than deferred to flush time.
 *
 * <p><b>Read path.</b> {@code toServiceModel} leaves the {@link RefDto} references and
 * {@code imageUrl} unset (they are resolved in {@link #resolveReadModel}) and copies the prices,
 * {@code website} and {@code active}. The referenced rows are localized to {@link RefDto} objects
 * ({@code id} + localized {@code name}, RU when the request locale is {@code ru}, else PL — PL
 * fallback), and the {@code imageUrl} is stamped from the entity's stored object key via
 * {@link ImageStorage#toCdnUrl(String)} (null key ⇒ null URL). The computed price ranges are
 * populated later by the {@code PriceRangeResolver} (tasks 7.1/7.2), not here.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class ConstructionMaterialServiceMapper
        implements ServiceToDaoMapper<ConstructionMaterialEntity, ConstructionMaterialServiceModel,
        ConstructionMaterialServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    @Autowired
    protected ImageStorage imageStorage;

    protected ConstructionMaterialTypeEntity typeRef(Long id) {
        return id == null ? null : entityManager.getReference(ConstructionMaterialTypeEntity.class, id);
    }

    protected MaterialProducerEntity producerRef(Long id) {
        return id == null ? null : entityManager.getReference(MaterialProducerEntity.class, id);
    }

    protected MaterialSellerEntity sellerRef(Long id) {
        return id == null ? null : entityManager.getReference(MaterialSellerEntity.class, id);
    }

    protected MeasurementUnitEntity unitRef(Long id) {
        return id == null ? null : entityManager.getReference(MeasurementUnitEntity.class, id);
    }

    protected CurrencyEntity currencyRef(Long id) {
        return id == null ? null : entityManager.getReference(CurrencyEntity.class, id);
    }

    protected OfferPackageEntity offerPackageRef(Long id) {
        return id == null ? null : entityManager.getReference(OfferPackageEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "type", expression = "java(typeRef(source.getTypeId()))")
    @Mapping(target = "producer", expression = "java(producerRef(source.getProducerId()))")
    @Mapping(target = "seller", expression = "java(sellerRef(source.getSellerId()))")
    @Mapping(target = "unit", expression = "java(unitRef(source.getUnitId()))")
    @Mapping(target = "currency", expression = "java(currencyRef(source.getCurrencyId()))")
    @Mapping(target = "packages", ignore = true)
    public abstract ConstructionMaterialEntity toCreateDaoModel(ConstructionMaterialServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "type", expression = "java(typeRef(source.getTypeId()))")
    @Mapping(target = "producer", expression = "java(producerRef(source.getProducerId()))")
    @Mapping(target = "seller", expression = "java(sellerRef(source.getSellerId()))")
    @Mapping(target = "unit", expression = "java(unitRef(source.getUnitId()))")
    @Mapping(target = "currency", expression = "java(currencyRef(source.getCurrencyId()))")
    @Mapping(target = "packages", ignore = true)
    public abstract void updateFields(ConstructionMaterialServiceExtendedModel source,
                                      @MappingTarget ConstructionMaterialEntity target);

    @Override
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "producer", ignore = true)
    @Mapping(target = "seller", ignore = true)
    @Mapping(target = "unit", ignore = true)
    @Mapping(target = "currency", ignore = true)
    @Mapping(target = "packages", ignore = true)
    @Mapping(target = "imageUrl", ignore = true)
    @Mapping(target = "priceRanges", ignore = true)
    public abstract ConstructionMaterialServiceModel toServiceModel(ConstructionMaterialEntity source);

    @Override
    @Mapping(target = "typeId", source = "type.id")
    @Mapping(target = "producerId", source = "producer.id")
    @Mapping(target = "sellerId", source = "seller.id")
    @Mapping(target = "unitId", source = "unit.id")
    @Mapping(target = "currencyId", source = "currency.id")
    @Mapping(target = "offerPackageIds", ignore = true)
    public abstract ConstructionMaterialServiceExtendedModel toServiceExtendedModel(ConstructionMaterialEntity source);

    /**
     * Rebuilds the {@code @ManyToMany packages} set on the entity from the write model's
     * {@code offerPackageIds}, in place, as managed {@link OfferPackageEntity} references
     * (clears then re-adds so an update replaces the membership). A null/empty id set clears the
     * membership — the service normalize step rejects an empty set before this runs, so a persisted
     * material always carries at least one package (Requirement 5.1).
     */
    @AfterMapping
    protected void buildPackages(@MappingTarget ConstructionMaterialEntity target,
                                 ConstructionMaterialServiceExtendedModel source) {
        Set<OfferPackageEntity> packages = target.getPackages();
        packages.clear();
        Set<Long> ids = source.getOfferPackageIds();
        if (ids == null) {
            return;
        }
        for (Long id : ids) {
            if (id != null) {
                packages.add(offerPackageRef(id));
            }
        }
    }

    /**
     * Populates the extended read model's {@code offerPackageIds} from the entity's
     * {@code @ManyToMany packages} set (each member's id). Built in an {@code @AfterMapping} because
     * the ManyToMany collection cannot be expressed by a single source path.
     */
    @AfterMapping
    protected void collectOfferPackageIds(@MappingTarget ConstructionMaterialServiceExtendedModel target,
                                          ConstructionMaterialEntity source) {
        Set<OfferPackageEntity> packages = source.getPackages();
        if (packages == null) {
            return;
        }
        for (OfferPackageEntity pkg : packages) {
            if (pkg != null) {
                target.getOfferPackageIds().add(pkg.getId());
            }
        }
    }

    /**
     * Resolves the read model's localized {@link RefDto} references and the CDN {@code imageUrl}.
     * Each reference row is localized to {@code (id, name)} with the per-request locale rule (RU when
     * the request locale language is {@code ru}, else PL — PL fallback); {@code packages} becomes an
     * ordered {@code List<RefDto>}. The {@code imageUrl} is resolved null-safely from the stored GCS
     * object key via {@link ImageStorage#toCdnUrl(String)} (never persisting the URL — Requirement
     * 4.7, 8.4).
     */
    @AfterMapping
    protected void resolveReadModel(@MappingTarget ConstructionMaterialServiceModel target,
                                    ConstructionMaterialEntity source) {
        boolean ru = isRussianLocale();
        target.setType(ref(source.getType(), ru));
        target.setProducer(ref(source.getProducer(), ru));
        target.setSeller(ref(source.getSeller(), ru));
        target.setUnit(ref(source.getUnit(), ru));
        target.setCurrency(ref(source.getCurrency(), ru));

        List<RefDto> packages = new ArrayList<>();
        if (source.getPackages() != null) {
            for (OfferPackageEntity pkg : source.getPackages()) {
                if (pkg != null) {
                    packages.add(new RefDto(pkg.getId(), ru ? pkg.getNameRU() : pkg.getNamePL()));
                }
            }
        }
        target.setPackages(packages);

        target.setImageUrl(imageStorage.toCdnUrl(source.getImage()));
    }

    private static RefDto ref(ConstructionMaterialTypeEntity e, boolean ru) {
        return e == null ? null : new RefDto(e.getId(), ru ? e.getNameRU() : e.getNamePL());
    }

    private static RefDto ref(MaterialProducerEntity e, boolean ru) {
        return e == null ? null : new RefDto(e.getId(), ru ? e.getNameRU() : e.getNamePL());
    }

    private static RefDto ref(MaterialSellerEntity e, boolean ru) {
        return e == null ? null : new RefDto(e.getId(), ru ? e.getNameRU() : e.getNamePL());
    }

    private static RefDto ref(MeasurementUnitEntity e, boolean ru) {
        return e == null ? null : new RefDto(e.getId(), ru ? e.getNameRU() : e.getNamePL());
    }

    private static RefDto ref(CurrencyEntity e, boolean ru) {
        return e == null ? null : new RefDto(e.getId(), ru ? e.getNameRU() : e.getNamePL());
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
