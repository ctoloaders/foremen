package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.MaterialProducerServiceExtendedModel;
import com.foremen.service.model.MaterialProducerServiceModel;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

/**
 * Service mapper for {@link MaterialProducerEntity}. An <b>abstract class</b> (following the
 * FOR-04 {@code RoomServiceMapper}/{@code WorkPriceServiceMapper} pattern) so it can hold the
 * injected shared {@link ImageStorage} seam and resolve the read-time CDN {@code imageUrl} from the
 * stored GCS object key (FOR-04-17, Requirement 8.4).
 *
 * <p>The write path carries the raw {@code image} object key straight through to the entity
 * (auto-mapped, same field name). The read path leaves the entity's {@code image} key on the
 * entity and, in an {@code @AfterMapping}, stamps the resolved {@code imageUrl} onto the
 * {@link MaterialProducerServiceModel} via {@link ImageStorage#toCdnUrl(String)} (null key → null
 * URL). {@code code} stays immutable on update.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class MaterialProducerServiceMapper
        implements ServiceToDaoMapper<MaterialProducerEntity, MaterialProducerServiceModel, MaterialProducerServiceExtendedModel> {

    @Autowired
    protected ImageStorage imageStorage;

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    public abstract MaterialProducerEntity toCreateDaoModel(MaterialProducerServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    public abstract void updateFields(MaterialProducerServiceExtendedModel source, @MappingTarget MaterialProducerEntity target);

    @Override
    @Mapping(target = "imageUrl", ignore = true)
    public abstract MaterialProducerServiceModel toServiceModel(MaterialProducerEntity source);

    /**
     * Resolves the entity's stored GCS object key ({@code image}) into the CDN {@code imageUrl} on
     * the read model via {@link ImageStorage#toCdnUrl(String)} — a null-safe, read-time resolution
     * (null key → null URL), never persisting the URL (FOR-04-17, Requirement 8.4).
     */
    @AfterMapping
    protected void resolveImageUrl(@MappingTarget MaterialProducerServiceModel target, MaterialProducerEntity source) {
        target.setImageUrl(imageStorage.toCdnUrl(source.getImage()));
    }
}
