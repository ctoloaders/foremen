package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.RoleServiceExtendedModel;
import com.foremen.service.model.RoleServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface RoleControllerMapper extends ControllerToServiceMapper<
        RoleServiceModel,
        RoleServiceExtendedModel,
        RoleDtoModel,
        RoleDtoExtendedModel,
        RoleCreateRequest,
        RoleCreateResponse,
        RoleUpdateRequest,
        RoleUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "system", expression = "java(false)")
    RoleServiceExtendedModel toServiceExtendedModel(RoleCreateRequest source);

    @Override
    @Mapping(target = "system", ignore = true)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    RoleServiceExtendedModel toUpdateServiceExtendedModel(RoleUpdateRequest source);
}
