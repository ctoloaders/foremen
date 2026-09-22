package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.AssortmentGroupEntity;
import com.foremen.dao.model.AssortmentLineItemEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.AssortmentLineItemServiceExtendedModel;
import com.foremen.service.model.AssortmentLineItemServiceModel;
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
 * Service mapper for {@link AssortmentLineItemEntity} (FOR-05-04, Requirement 6.1, 6.2, 6.6, 6.7).
 *
 * <p>An <b>abstract class</b> (not an interface) so it can hold an injected {@link EntityManager}
 * used to turn the flat {@code assortmentGroupId}/{@code offerPackageId}/{@code typicalProductId}
 * into managed references via {@code getReference(...)} (no SELECT), mirroring
 * {@code WorkMaterialConsumptionServiceMapper}.
 *
 * <p>Reads. {@code toServiceModel} maps the price/quantity fields directly and localizes
 * {@code name} (PL fallback) plus the referenced group/package/typical-product display names in
 * {@link #resolveReferencedNames} using the request-locale rule (RU when the request locale
 * language is {@code ru}, PL otherwise). {@code typicalProductId}/{@code typicalProductName} are
 * assistive/provenance only (Requirement 6.6, 6.7) — resolving them never touches
 * {@code minPrice}/{@code avgPrice}/{@code maxPrice}.
 *
 * <p>Writes. {@code toCreateDaoModel}/{@code updateFields} set the {@code group}/
 * {@code offerPackage}/{@code typicalProduct} references via {@code expression} mappings and copy
 * the raw i18n + price/quantity fields straight through. {@code typicalProductId} is nullable and,
 * per Requirement 6.6/6.7, is never read as a source for {@code minPrice}/{@code avgPrice}/
 * {@code maxPrice} — those three fields are always taken directly from the write model.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class AssortmentLineItemServiceMapper
        implements ServiceToDaoMapper<AssortmentLineItemEntity, AssortmentLineItemServiceModel,
        AssortmentLineItemServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected AssortmentGroupEntity assortmentGroupRef(Long id) {
        return id == null ? null : entityManager.getReference(AssortmentGroupEntity.class, id);
    }

    protected OfferPackageEntity offerPackageRef(Long id) {
        return id == null ? null : entityManager.getReference(OfferPackageEntity.class, id);
    }

    protected MaterialEntity typicalProductRef(Long id) {
        return id == null ? null : entityManager.getReference(MaterialEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "group", expression = "java(assortmentGroupRef(source.assortmentGroupId()))")
    @Mapping(target = "offerPackage", expression = "java(offerPackageRef(source.offerPackageId()))")
    @Mapping(target = "typicalProduct", expression = "java(typicalProductRef(source.typicalProductId()))")
    @Mapping(target = "nameRU", source = "nameRU")
    @Mapping(target = "namePL", source = "namePL")
    @Mapping(target = "minPrice", source = "minPrice")
    @Mapping(target = "avgPrice", source = "avgPrice")
    @Mapping(target = "maxPrice", source = "maxPrice")
    @Mapping(target = "qtyRef50", source = "qtyRef50")
    public abstract AssortmentLineItemEntity toCreateDaoModel(AssortmentLineItemServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "group", expression = "java(assortmentGroupRef(source.assortmentGroupId()))")
    @Mapping(target = "offerPackage", expression = "java(offerPackageRef(source.offerPackageId()))")
    @Mapping(target = "typicalProduct", expression = "java(typicalProductRef(source.typicalProductId()))")
    @Mapping(target = "nameRU", source = "nameRU")
    @Mapping(target = "namePL", source = "namePL")
    @Mapping(target = "minPrice", source = "minPrice")
    @Mapping(target = "avgPrice", source = "avgPrice")
    @Mapping(target = "maxPrice", source = "maxPrice")
    @Mapping(target = "qtyRef50", source = "qtyRef50")
    public abstract void updateFields(AssortmentLineItemServiceExtendedModel source,
                                      @MappingTarget AssortmentLineItemEntity target);

    @Override
    @Mapping(target = "assortmentGroupId", source = "group.id")
    @Mapping(target = "assortmentGroupName", ignore = true)
    @Mapping(target = "offerPackageId", source = "offerPackage.id")
    @Mapping(target = "offerPackageName", ignore = true)
    @Mapping(target = "name", ignore = true)
    @Mapping(target = "minPrice", source = "minPrice")
    @Mapping(target = "avgPrice", source = "avgPrice")
    @Mapping(target = "maxPrice", source = "maxPrice")
    @Mapping(target = "qtyRef50", source = "qtyRef50")
    @Mapping(target = "typicalProductId", source = "typicalProduct.id")
    @Mapping(target = "typicalProductName", ignore = true)
    public abstract AssortmentLineItemServiceModel toServiceModel(AssortmentLineItemEntity source);

    @Override
    @Mapping(target = "assortmentGroupId", source = "group.id")
    @Mapping(target = "offerPackageId", source = "offerPackage.id")
    @Mapping(target = "nameRU", source = "nameRU")
    @Mapping(target = "namePL", source = "namePL")
    @Mapping(target = "minPrice", source = "minPrice")
    @Mapping(target = "avgPrice", source = "avgPrice")
    @Mapping(target = "maxPrice", source = "maxPrice")
    @Mapping(target = "qtyRef50", source = "qtyRef50")
    @Mapping(target = "typicalProductId", source = "typicalProduct.id")
    public abstract AssortmentLineItemServiceExtendedModel toServiceExtendedModel(AssortmentLineItemEntity source);

    /**
     * Resolves the referenced assortment group's/offer package's/typical product's localized
     * display names using the same per-request locale rule the framework uses for {@code name}
     * (RU when the request locale language is {@code ru}, PL otherwise). The typical-product name
     * is assistive/provenance display only (Requirement 6.6, 6.7) and is resolved independently of
     * the price fields, which are never touched here.
     */
    @AfterMapping
    protected void resolveReferencedNames(@MappingTarget AssortmentLineItemServiceModel target,
                                          AssortmentLineItemEntity source) {
        boolean ru = isRussianLocale();
        AssortmentGroupEntity group = source.getGroup();
        if (group != null) {
            target.setAssortmentGroupName(ru ? group.getNameRU() : group.getNamePL());
        }
        OfferPackageEntity pkg = source.getOfferPackage();
        if (pkg != null) {
            target.setOfferPackageName(ru ? pkg.getNameRU() : pkg.getNamePL());
        }
        MaterialEntity typicalProduct = source.getTypicalProduct();
        if (typicalProduct != null) {
            target.setTypicalProductName(ru ? typicalProduct.getNameRU() : typicalProduct.getNamePL());
        }
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
