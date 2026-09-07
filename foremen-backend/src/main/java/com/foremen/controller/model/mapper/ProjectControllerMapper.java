package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.CreateProjectRequest;
import com.foremen.controller.model.ProjectCreateResponse;
import com.foremen.controller.model.ProjectListDto;
import com.foremen.controller.model.ProjectReadDto;
import com.foremen.controller.model.ProjectUpdateRequest;
import com.foremen.controller.model.ProjectUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.ProjectServiceExtendedModel;
import com.foremen.service.model.ProjectServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Controller-layer mapper for {@code ProjectController} (FOR-04-13). Bridges the inbound
 * request DTOs and the outbound projection DTOs to/from the service models.
 *
 * <p>The list/read/create/update projection DTOs carry a {@code members} collection and a derived
 * {@code client} that do <strong>not</strong> exist on the service models — they are projected from
 * the read-only {@code ProjectEntity.members} collection at projection time (task 7.1). Those two
 * targets are therefore {@code ignore}d here so MapStruct maps only the base descriptive fields; the
 * projection layer populates {@code members}/{@code client} separately. The custom create is handled
 * by {@code ProjectService.createProject(...)}; the generic {@code toServiceExtendedModel(...)} exists
 * only to satisfy the {@link ControllerToServiceMapper} contract (the generic create path is disabled
 * at the service layer).
 */
@Mapper(config = ForemenMapperConfig.class)
public interface ProjectControllerMapper extends ControllerToServiceMapper<
        ProjectServiceModel,
        ProjectServiceExtendedModel,
        ProjectListDto,
        ProjectReadDto,
        CreateProjectRequest,
        ProjectCreateResponse,
        ProjectUpdateRequest,
        ProjectUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    ProjectServiceExtendedModel toServiceExtendedModel(CreateProjectRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    ProjectServiceExtendedModel toUpdateServiceExtendedModel(ProjectUpdateRequest source);

    @Override
    @Mapping(target = "members", ignore = true)
    @Mapping(target = "client", ignore = true)
    ProjectListDto toDto(ProjectServiceModel source);

    @Override
    @Mapping(target = "members", ignore = true)
    @Mapping(target = "client", ignore = true)
    ProjectReadDto toExtendedDto(ProjectServiceExtendedModel source);

    @Override
    @Mapping(target = "members", ignore = true)
    @Mapping(target = "client", ignore = true)
    ProjectCreateResponse toCreateResponse(ProjectServiceExtendedModel source);

    @Override
    @Mapping(target = "members", ignore = true)
    @Mapping(target = "client", ignore = true)
    ProjectUpdateResponse toUpdateResponse(ProjectServiceExtendedModel source);
}
