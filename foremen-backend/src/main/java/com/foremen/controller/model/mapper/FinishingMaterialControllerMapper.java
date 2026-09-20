package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.FinishingMaterialCreateRequest;
import com.foremen.controller.model.FinishingMaterialCreateResponse;
import com.foremen.controller.model.FinishingMaterialDtoExtendedModel;
import com.foremen.controller.model.FinishingMaterialDtoModel;
import com.foremen.controller.model.FinishingMaterialUpdateRequest;
import com.foremen.controller.model.FinishingMaterialUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.FinishingMaterialServiceExtendedModel;
import com.foremen.service.model.FinishingMaterialServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Controller mapper for the finishing-material vertical (FOR-04-18, task 2.2).
 *
 * <p>The sibling of {@code ConstructionMaterialControllerMapper}: an <b>abstract class</b>
 * (following the FOR-04 {@code MaterialProducerControllerMapper} pattern) so it can hold the
 * injected shared {@link ImageStorage} seam — the write-path extended DTO and the create/update
 * responses (built from the write model, which holds only the raw {@code photo} object key) resolve
 * the CDN {@code photoUrl} via {@link ImageStorage#toCdnUrl(String)}.
 *
 * <p><b>Reads.</b> {@link #toDto} maps the read service model — whose localized
 * {@link com.foremen.controller.model.RefDto} references, derived {@code label} and resolved
 * {@code photoUrl} are already populated by the {@code FinishingMaterialServiceMapper} — straight
 * through by field name. {@link #toExtendedDto} additionally carries the raw reference ids the edit
 * form needs; its localized {@code RefDto} references and derived {@code label} are left unset on the
 * write model (they are read-only concerns) while {@code photoUrl} is resolved from the object key.
 *
 * <p><b>Writes.</b> {@link #toServiceExtendedModel} / {@link #toUpdateServiceExtendedModel} carry the
 * raw reference ids and the {@code photo} object key straight through and default {@code active} to
 * {@code true} when the request omits it (Requirement 2.8). There is no {@code code}/{@code name} to
 * protect on update, so the update request maps like create.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class FinishingMaterialControllerMapper implements ControllerToServiceMapper<
        FinishingMaterialServiceModel,
        FinishingMaterialServiceExtendedModel,
        FinishingMaterialDtoModel,
        FinishingMaterialDtoExtendedModel,
        FinishingMaterialCreateRequest,
        FinishingMaterialCreateResponse,
        FinishingMaterialUpdateRequest,
        FinishingMaterialUpdateResponse> {

    @Autowired
    protected ImageStorage imageStorage;

    /**
     * Resolves the CDN URL from a stored GCS object key. Annotated {@link Named} so MapStruct does
     * NOT auto-select this {@code String -> String} helper as an implicit property mapping method
     * (which would otherwise wrap unrelated same-typed fields such as {@code model}/{@code sku}/
     * {@code link}, nulling them). It is still invoked explicitly from the {@code photoUrl}
     * {@code expression} mappings.
     */
    @Named("toCdnUrl")
    protected String toCdnUrl(String objectKey) {
        return imageStorage.toCdnUrl(objectKey);
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    public abstract FinishingMaterialServiceExtendedModel toServiceExtendedModel(FinishingMaterialCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    public abstract FinishingMaterialServiceExtendedModel toUpdateServiceExtendedModel(FinishingMaterialUpdateRequest source);

    @Override
    public abstract FinishingMaterialDtoModel toDto(FinishingMaterialServiceModel source);

    @Override
    @Mapping(target = "label", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "material", ignore = true)
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "producer", ignore = true)
    @Mapping(target = "unit", ignore = true)
    @Mapping(target = "packages", ignore = true)
    @Mapping(target = "photoUrl", expression = "java(toCdnUrl(source.getPhoto()))")
    public abstract FinishingMaterialDtoExtendedModel toExtendedDto(FinishingMaterialServiceExtendedModel source);

    @Override
    @Mapping(target = "label", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "material", ignore = true)
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "producer", ignore = true)
    @Mapping(target = "unit", ignore = true)
    @Mapping(target = "packages", ignore = true)
    @Mapping(target = "photoUrl", expression = "java(toCdnUrl(source.getPhoto()))")
    public abstract FinishingMaterialCreateResponse toCreateResponse(FinishingMaterialServiceExtendedModel source);

    @Override
    @Mapping(target = "label", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "material", ignore = true)
    @Mapping(target = "type", ignore = true)
    @Mapping(target = "producer", ignore = true)
    @Mapping(target = "unit", ignore = true)
    @Mapping(target = "packages", ignore = true)
    @Mapping(target = "photoUrl", expression = "java(toCdnUrl(source.getPhoto()))")
    public abstract FinishingMaterialUpdateResponse toUpdateResponse(FinishingMaterialServiceExtendedModel source);
}
