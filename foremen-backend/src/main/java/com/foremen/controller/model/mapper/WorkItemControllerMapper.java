package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.WorkItemServiceExtendedModel;
import com.foremen.service.model.WorkItemServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface WorkItemControllerMapper extends ControllerToServiceMapper<
        WorkItemServiceModel,
        WorkItemServiceExtendedModel,
        WorkItemDtoModel,
        WorkItemDtoExtendedModel,
        WorkItemCreateRequest,
        WorkItemCreateResponse,
        WorkItemUpdateRequest,
        WorkItemUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    WorkItemServiceExtendedModel toServiceExtendedModel(WorkItemCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    WorkItemServiceExtendedModel toUpdateServiceExtendedModel(WorkItemUpdateRequest source);
}
