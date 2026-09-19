package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.RefDto;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.FinishingMaterialServiceExtendedModel;
import com.foremen.service.model.FinishingMaterialServiceModel;
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
 * Service mapper for {@link FinishingMaterialEntity} (FOR-04-18, task 2.2).
 *
 * <p>The sibling of {@code ConstructionMaterialServiceMapper}: an <b>abstract class</b> (following
 * the FOR-04 {@code WorkPriceServiceMapper}/{@code RoomServiceMapper}/
 * {@code MaterialProducerServiceMapper} pattern) so it can hold the injected {@link EntityManager}
 * (to turn the flat write-model reference ids into managed references via {@code getReference(...)}
 * without a SELECT) and the shared {@link ImageStorage} seam (to resolve the read-time CDN
 * {@code photoUrl} from the stored GCS object key — Requirement 2.8).
 *
 * <p><b>Write path.</b> {@code toCreateDaoModel}/{@code updateFields} map the free-text
 * {@code model}/{@code sku}/{@code features}, the three prices, the {@code link}, the {@code photo}
 * object key and {@code active} straight through (same field names), and turn the flat
 * {@code categoryId}/{@code materialId}/{@code typeId}/{@code producerId}/{@code unitId} into managed
 * {@code @ManyToOne} references via {@code getReference(...)}. The {@code @ManyToMany packages} set
 * is (re)built in {@link #buildPackages} from {@code offerPackageIds} as managed
 * {@link OfferPackageEntity} references. Reference <em>existence</em> is asserted by the
 * {@code FinishingMaterialService} normalize step before this mapper runs, so a dangling id is
 * rejected there rather than deferred to flush time.
 *
 * <p><b>Read path.</b> {@code toServiceModel} leaves the {@link RefDto} references, the derived
 * {@code label} and {@code photoUrl} unset (resolved in {@link #resolveReadModel}) and copies the
 * free-text fields, the prices, the {@code link} and {@code active}. The referenced rows are
 * localized to {@link RefDto} objects ({@code id} + localized {@code name}, RU when the request
 * locale is {@code ru}, else PL — PL fallback), the {@code photoUrl} is stamped from the entity's
 * stored object key via {@link ImageStorage#toCdnUrl(String)} (null key ⇒ null URL), and the derived
 * {@code label} is built from the localized {@code material} name + {@code model}
 * ({@code "{material} — {model}"}, or just {@code {material}} when {@code model} is blank) —
 * Requirement 2.9. Unlike {@code ConstructionMaterial} there is no localized {@code name} on the
 * entity and no computed price ranges.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class FinishingMaterialServiceMapper
        implements ServiceToDaoMapper<FinishingMaterialEntity, FinishingMaterialServiceModel,
        FinishingMaterialServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    @Autowired
    protected ImageStorage imageStorage;

    protected MaterialCategoryEntity categoryRef(Long id) {
        return id == null ? null : entityManager.getReference(MaterialCategoryEntity.class, id);
    }

    protected MaterialEntity materialRef(Long id) {
        return id == null ? null : entityManager.getReference(MaterialEntity.class, id);
    }

    protected MaterialTypeEntity typeRef(Long id) {
        return id == null ? null : entityManager.getReference(MaterialTypeEntity.class, id);
    }

    protected MaterialProducerEntity producerRef(Long id) {
        return id == null ? null : entityManager.getReference(MaterialProducerEntity.class, id);
    }

    protected MeasurementUnitEntity unitRef(Long id) {
        return id == null ? null : entityManager.getReference(MeasurementUnitEntity.class, id);
    }

    protected OfferPackageEntity offerPackageRef(Long id) {
        return id == null ? null : entityManager.getReference(OfferPackageEntity.class, id);
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        // FinishingMaterial has no localized name/code fields (Requirement 1.1) — nothing to normalize.
        return Set.of();
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "category", expression = "java(categoryRef(source.getCategoryId()))")
    @Mapping(target = "material", expression = "java(materialRef(source.getMaterialId()))")
    @Mapping(target = "type", expression = "java(typeRef(source.getTypeId()))")
    @Mapping(target = "producer", expression = "java(producerRef(source.getProducerId()))")
    @Mapping(target = "unit", expression = "java(unitRef(source.getUnitId()))")
    @Mapping(target = "packages", ignore = true)
    public abstract FinishingMaterialEntity toCreateDaoModel(FinishingMaterialServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "category", expression = "java(categoryRef(source.getCategoryId()))")
    @Mapping(target = "material", expression = "java(materialRef(source.getMaterialId()))")
    @Mapping(target = "type", expression = "java(typeRef(source.getTypeId()))")
    @Mapping(target = "producer", expression = "java(producerRef(source.getProducerId()))")
    @Mapping(target = "unit", expression = "java(unitRef(source.getUnitId()))")
    @Mapping(target = "packages", ignore = true)
    public abstract void updateFields(FinishingMaterialServiceExtendedModel source,
                                      @MappingTarget FinishingMaterialEntity target);

    @Override
    @Mapping(target = "label", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "material", ignore = true)
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "producer", ignore = true)
    @Mapping(target = "unit", ignore = true)
    @Mapping(target = "packages", ignore = true)
    @Mapping(target = "photoUrl", ignore = true)
    public abstract FinishingMaterialServiceModel toServiceModel(FinishingMaterialEntity source);

    @Override
    @Mapping(target = "categoryId", source = "category.id")
    @Mapping(target = "materialId", source = "material.id")
    @Mapping(target = "typeId", source = "type.id")
    @Mapping(target = "producerId", source = "producer.id")
    @Mapping(target = "unitId", source = "unit.id")
    @Mapping(target = "offerPackageIds", ignore = true)
    public abstract FinishingMaterialServiceExtendedModel toServiceExtendedModel(FinishingMaterialEntity source);

    /**
     * Rebuilds the {@code @ManyToMany packages} set on the entity from the write model's
     * {@code offerPackageIds}, in place, as managed {@link OfferPackageEntity} references
     * (clears then re-adds so an update replaces the membership). A null/empty id set clears the
     * membership — the service normalize step rejects an empty set before this runs, so a persisted
     * material always carries at least one package (Requirement 3.1).
     */
    @AfterMapping
    protected void buildPackages(@MappingTarget FinishingMaterialEntity target,
                                 FinishingMaterialServiceExtendedModel source) {
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
    protected void collectOfferPackageIds(@MappingTarget FinishingMaterialServiceExtendedModel target,
                                          FinishingMaterialEntity source) {
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
     * Resolves the read model's localized {@link RefDto} references, the derived {@code label} and
     * the CDN {@code photoUrl}. Each reference row is localized to {@code (id, name)} with the
     * per-request locale rule (RU when the request locale language is {@code ru}, else PL — PL
     * fallback); {@code packages} becomes an ordered {@code List<RefDto>}. The {@code label} is built
     * from the localized {@code material} name + {@code model} (Requirement 2.9) and the
     * {@code photoUrl} is resolved null-safely from the stored GCS object key via
     * {@link ImageStorage#toCdnUrl(String)} (never persisting the URL — Requirement 2.8).
     */
    @AfterMapping
    protected void resolveReadModel(@MappingTarget FinishingMaterialServiceModel target,
                                    FinishingMaterialEntity source) {
        boolean ru = isRussianLocale();
        target.setCategory(ref(source.getCategory(), ru));
        target.setMaterial(ref(source.getMaterial(), ru));
        target.setType(ref(source.getType(), ru));
        target.setProducer(ref(source.getProducer(), ru));
        target.setUnit(ref(source.getUnit(), ru));

        List<RefDto> packages = new ArrayList<>();
        if (source.getPackages() != null) {
            for (OfferPackageEntity pkg : source.getPackages()) {
                if (pkg != null) {
                    packages.add(new RefDto(pkg.getId(), ru ? pkg.getNameRU() : pkg.getNamePL()));
                }
            }
        }
        target.setPackages(packages);

        target.setLabel(buildLabel(source, ru));
        target.setPhotoUrl(imageStorage.toCdnUrl(source.getPhoto()));
    }

    /**
     * Builds the derived, never-persisted list label: the localized {@code material} name joined
     * with {@code model} ({@code "{material} — {model}"}), or just the material name when
     * {@code model} is blank (Requirement 2.9). Returns just the {@code model} when the material is
     * absent, or {@code null} when neither is present.
     */
    private static String buildLabel(FinishingMaterialEntity source, boolean ru) {
        String materialName = source.getMaterial() == null
                ? null
                : (ru ? source.getMaterial().getNameRU() : source.getMaterial().getNamePL());
        String model = source.getModel();
        boolean hasMaterial = materialName != null && !materialName.isBlank();
        boolean hasModel = model != null && !model.isBlank();
        if (hasMaterial && hasModel) {
            return materialName + " — " + model;
        }
        if (hasMaterial) {
            return materialName;
        }
        return hasModel ? model : null;
    }

    private static RefDto ref(MaterialCategoryEntity e, boolean ru) {
        return e == null ? null : new RefDto(e.getId(), ru ? e.getNameRU() : e.getNamePL());
    }

    private static RefDto ref(MaterialEntity e, boolean ru) {
        return e == null ? null : new RefDto(e.getId(), ru ? e.getNameRU() : e.getNamePL());
    }

    private static RefDto ref(MaterialTypeEntity e, boolean ru) {
        return e == null ? null : new RefDto(e.getId(), ru ? e.getNameRU() : e.getNamePL());
    }

    private static RefDto ref(MaterialProducerEntity e, boolean ru) {
        return e == null ? null : new RefDto(e.getId(), ru ? e.getNameRU() : e.getNamePL());
    }

    private static RefDto ref(MeasurementUnitEntity e, boolean ru) {
        return e == null ? null : new RefDto(e.getId(), ru ? e.getNameRU() : e.getNamePL());
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
