package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.RefDto;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
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
 * {@code @ManyToOne} references via {@code getReference(...)}. The material-side package dimension
 * was collapsed by FOR-05-04-UI (Requirement 5), so there is no longer any package binding to build.
 * Reference <em>existence</em> is asserted by the {@code ConstructionMaterialService} normalize step
 * before this mapper runs, so a dangling id is rejected there rather than deferred to flush time.
 *
 * <p><b>Read path.</b> {@code toServiceModel} leaves the {@link RefDto} references and
 * {@code imageUrl} unset (they are resolved in {@link #resolveReadModel}) and copies the prices,
 * {@code website} and {@code active}. The referenced rows are localized to {@link RefDto} objects
 * ({@code id} + localized {@code name}, RU when the request locale is {@code ru}, else PL — PL
 * fallback), and the {@code imageUrl} is stamped from the entity's stored object key via
 * {@link ImageStorage#toCdnUrl(String)} (null key ⇒ null URL). The computed price ranges are
 * populated later by the {@code PriceRangeResolver}, not here.
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
    public abstract ConstructionMaterialEntity toCreateDaoModel(ConstructionMaterialServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "type", expression = "java(typeRef(source.getTypeId()))")
    @Mapping(target = "producer", expression = "java(producerRef(source.getProducerId()))")
    @Mapping(target = "seller", expression = "java(sellerRef(source.getSellerId()))")
    @Mapping(target = "unit", expression = "java(unitRef(source.getUnitId()))")
    @Mapping(target = "currency", expression = "java(currencyRef(source.getCurrencyId()))")
    public abstract void updateFields(ConstructionMaterialServiceExtendedModel source,
                                      @MappingTarget ConstructionMaterialEntity target);

    @Override
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "producer", ignore = true)
    @Mapping(target = "seller", ignore = true)
    @Mapping(target = "unit", ignore = true)
    @Mapping(target = "currency", ignore = true)
    @Mapping(target = "imageUrl", ignore = true)
    @Mapping(target = "priceRanges", ignore = true)
    public abstract ConstructionMaterialServiceModel toServiceModel(ConstructionMaterialEntity source);

    @Override
    @Mapping(target = "typeId", source = "type.id")
    @Mapping(target = "producerId", source = "producer.id")
    @Mapping(target = "sellerId", source = "seller.id")
    @Mapping(target = "unitId", source = "unit.id")
    @Mapping(target = "currencyId", source = "currency.id")
    public abstract ConstructionMaterialServiceExtendedModel toServiceExtendedModel(ConstructionMaterialEntity source);

    /**
     * Resolves the read model's localized {@link RefDto} references and the CDN {@code imageUrl}.
     * Each reference row is localized to {@code (id, name)} with the per-request locale rule (RU when
     * the request locale language is {@code ru}, else PL — PL fallback). The {@code imageUrl} is
     * resolved null-safely from the stored GCS object key via {@link ImageStorage#toCdnUrl(String)}
     * (never persisting the URL — Requirement 4.7, 8.4).
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
