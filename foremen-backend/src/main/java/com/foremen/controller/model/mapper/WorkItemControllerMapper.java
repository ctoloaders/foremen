package com.foremen.controller.model.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.WorkItemCreateRequest;
import com.foremen.controller.model.WorkItemCreateResponse;
import com.foremen.controller.model.WorkItemDtoExtendedModel;
import com.foremen.controller.model.WorkItemDtoModel;
import com.foremen.controller.model.WorkItemUpdateRequest;
import com.foremen.controller.model.WorkItemUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.WorkItemServiceExtendedModel;
import com.foremen.service.model.WorkItemServiceModel;

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
