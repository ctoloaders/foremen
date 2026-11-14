package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.ProjectMaterialCreateRequest;
import com.foremen.controller.model.ProjectMaterialCreateResponse;
import com.foremen.controller.model.ProjectMaterialDtoExtendedModel;
import com.foremen.controller.model.ProjectMaterialDtoModel;
import com.foremen.controller.model.ProjectMaterialUpdateRequest;
import com.foremen.controller.model.ProjectMaterialUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.ProjectMaterialServiceExtendedModel;
import com.foremen.service.model.ProjectMaterialServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Controller mapper for Project_Materials (FOR-11-00 R3). Mirrors
 * {@code FinishingMaterialControllerMapper} with the extra {@code projectId}/{@code sellerId}
 * fields; {@code active} defaults to {@code true}.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class ProjectMaterialControllerMapper implements ControllerToServiceMapper<
        ProjectMaterialServiceModel,
        ProjectMaterialServiceExtendedModel,
        ProjectMaterialDtoModel,
        ProjectMaterialDtoExtendedModel,
        ProjectMaterialCreateRequest,
        ProjectMaterialCreateResponse,
        ProjectMaterialUpdateRequest,
        ProjectMaterialUpdateResponse> {

    @Autowired
    protected ImageStorage imageStorage;

    @Named("toCdnUrl")
    protected String toCdnUrl(String objectKey) {
        return imageStorage.toCdnUrl(objectKey);
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    public abstract ProjectMaterialServiceExtendedModel toServiceExtendedModel(ProjectMaterialCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    public abstract ProjectMaterialServiceExtendedModel toUpdateServiceExtendedModel(ProjectMaterialUpdateRequest source);

    @Override
    public abstract ProjectMaterialDtoModel toDto(ProjectMaterialServiceModel source);

    @Override
    @Mapping(target = "photoUrl", expression = "java(toCdnUrl(source.getPhoto()))")
    public abstract ProjectMaterialDtoExtendedModel toExtendedDto(ProjectMaterialServiceExtendedModel source);

    @Override
    @Mapping(target = "photoUrl", expression = "java(toCdnUrl(source.getPhoto()))")
    public abstract ProjectMaterialCreateResponse toCreateResponse(ProjectMaterialServiceExtendedModel source);

    @Override
    @Mapping(target = "photoUrl", expression = "java(toCdnUrl(source.getPhoto()))")
    public abstract ProjectMaterialUpdateResponse toUpdateResponse(ProjectMaterialServiceExtendedModel source);
}
