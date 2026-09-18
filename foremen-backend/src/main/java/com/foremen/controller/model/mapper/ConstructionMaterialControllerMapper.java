package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.ConstructionMaterialCreateRequest;
import com.foremen.controller.model.ConstructionMaterialCreateResponse;
import com.foremen.controller.model.ConstructionMaterialDtoExtendedModel;
import com.foremen.controller.model.ConstructionMaterialDtoModel;
import com.foremen.controller.model.ConstructionMaterialUpdateRequest;
import com.foremen.controller.model.ConstructionMaterialUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.ConstructionMaterialServiceExtendedModel;
import com.foremen.service.model.ConstructionMaterialServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Controller mapper for the construction-material vertical (FOR-04-17, task 5.2).
 *
 * <p>An <b>abstract class</b> (following the FOR-04 {@code MaterialProducerControllerMapper}
 * pattern) so it can hold the injected shared {@link ImageStorage} seam: the write-path extended
 * DTO and the create/update responses (built from the write model, which holds only the raw
 * {@code image} object key) resolve the CDN {@code imageUrl} via {@link ImageStorage#toCdnUrl(String)}.
 *
 * <p><b>Reads.</b> {@link #toDto} maps the read service model — whose localized {@link com.foremen.controller.model.RefDto}
 * references, resolved {@code imageUrl} and computed {@code priceRanges} are already populated by the
 * {@code ConstructionMaterialServiceMapper} / {@code PriceRangeResolver} — straight through by field
 * name. {@link #toExtendedDto} additionally carries the raw reference ids the edit form needs; its
 * localized {@code RefDto} references and {@code priceRanges} are left unset on the write model (they
 * are read-only concerns) while {@code imageUrl} is resolved from the object key.
 *
 * <p><b>Writes.</b> {@link #toServiceExtendedModel} / {@link #toUpdateServiceExtendedModel} carry the
 * raw reference ids and the {@code image} object key straight through and default {@code active} to
 * {@code true} when the request omits it (Requirement 4.7).
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class ConstructionMaterialControllerMapper implements ControllerToServiceMapper<
        ConstructionMaterialServiceModel,
        ConstructionMaterialServiceExtendedModel,
        ConstructionMaterialDtoModel,
        ConstructionMaterialDtoExtendedModel,
        ConstructionMaterialCreateRequest,
        ConstructionMaterialCreateResponse,
        ConstructionMaterialUpdateRequest,
        ConstructionMaterialUpdateResponse> {

    @Autowired
    protected ImageStorage imageStorage;

    /**
     * Resolves the CDN URL from a stored GCS object key. Annotated {@link Named} so MapStruct does
     * NOT auto-select this {@code String -> String} helper as an implicit property mapping method
     * (which would otherwise wrap unrelated same-typed fields such as {@code nameRU}/{@code namePL}/
     * {@code website}, nulling them). It is still invoked explicitly from the {@code imageUrl}
     * {@code expression} mappings.
     */
    @Named("toCdnUrl")
    protected String toCdnUrl(String objectKey) {
        return imageStorage.toCdnUrl(objectKey);
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    public abstract ConstructionMaterialServiceExtendedModel toServiceExtendedModel(ConstructionMaterialCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    public abstract ConstructionMaterialServiceExtendedModel toUpdateServiceExtendedModel(ConstructionMaterialUpdateRequest source);

    @Override
    public abstract ConstructionMaterialDtoModel toDto(ConstructionMaterialServiceModel source);

    @Override
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "producer", ignore = true)
    @Mapping(target = "seller", ignore = true)
    @Mapping(target = "unit", ignore = true)
    @Mapping(target = "currency", ignore = true)
    @Mapping(target = "packages", ignore = true)
    @Mapping(target = "name", ignore = true)
    @Mapping(target = "imageUrl", expression = "java(toCdnUrl(source.getImage()))")
    @Mapping(target = "priceRanges", ignore = true)
    public abstract ConstructionMaterialDtoExtendedModel toExtendedDto(ConstructionMaterialServiceExtendedModel source);

    @Override
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "producer", ignore = true)
    @Mapping(target = "seller", ignore = true)
    @Mapping(target = "unit", ignore = true)
    @Mapping(target = "currency", ignore = true)
    @Mapping(target = "packages", ignore = true)
    @Mapping(target = "name", ignore = true)
    @Mapping(target = "imageUrl", expression = "java(toCdnUrl(source.getImage()))")
    @Mapping(target = "priceRanges", ignore = true)
    public abstract ConstructionMaterialCreateResponse toCreateResponse(ConstructionMaterialServiceExtendedModel source);

    @Override
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "producer", ignore = true)
    @Mapping(target = "seller", ignore = true)
    @Mapping(target = "unit", ignore = true)
    @Mapping(target = "currency", ignore = true)
    @Mapping(target = "packages", ignore = true)
    @Mapping(target = "name", ignore = true)
    @Mapping(target = "imageUrl", expression = "java(toCdnUrl(source.getImage()))")
    @Mapping(target = "priceRanges", ignore = true)
    public abstract ConstructionMaterialUpdateResponse toUpdateResponse(ConstructionMaterialServiceExtendedModel source);
}
