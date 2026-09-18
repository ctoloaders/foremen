package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.MaterialProducerServiceExtendedModel;
import com.foremen.service.model.MaterialProducerServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Controller mapper for the material producer vertical. An <b>abstract class</b> so it can hold the
 * injected shared {@link ImageStorage} seam (FOR-04-17, Requirement 8.4): the write requests carry
 * the raw {@code image} object key straight through to the write service model, while the extended
 * read DTO and the create/update responses — built from the write model that holds the raw key —
 * resolve the CDN {@code imageUrl} via {@link ImageStorage#toCdnUrl(String)}. The list/read
 * {@link MaterialProducerDtoModel} simply carries through the {@code imageUrl} already resolved by
 * the service mapper.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class MaterialProducerControllerMapper implements ControllerToServiceMapper<
        MaterialProducerServiceModel,
        MaterialProducerServiceExtendedModel,
        MaterialProducerDtoModel,
        MaterialProducerDtoExtendedModel,
        MaterialProducerCreateRequest,
        MaterialProducerCreateResponse,
        MaterialProducerUpdateRequest,
        MaterialProducerUpdateResponse> {

    @Autowired
    protected ImageStorage imageStorage;

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    public abstract MaterialProducerServiceExtendedModel toServiceExtendedModel(MaterialProducerCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    public abstract MaterialProducerServiceExtendedModel toUpdateServiceExtendedModel(MaterialProducerUpdateRequest source);

    @Override
    public abstract MaterialProducerDtoModel toDto(MaterialProducerServiceModel source);

    @Override
    @Mapping(target = "imageUrl", expression = "java(imageStorage.toCdnUrl(source.image()))")
    public abstract MaterialProducerDtoExtendedModel toExtendedDto(MaterialProducerServiceExtendedModel source);

    @Override
    @Mapping(target = "imageUrl", expression = "java(imageStorage.toCdnUrl(source.image()))")
    public abstract MaterialProducerCreateResponse toCreateResponse(MaterialProducerServiceExtendedModel source);

    @Override
    @Mapping(target = "imageUrl", expression = "java(imageStorage.toCdnUrl(source.image()))")
    public abstract MaterialProducerUpdateResponse toUpdateResponse(MaterialProducerServiceExtendedModel source);
}
