package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.UserServiceExtendedModel;
import com.foremen.service.model.UserServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = ForemenMapperConfig.class)
public interface UserControllerMapper extends ControllerToServiceMapper<
        UserServiceModel,
        UserServiceExtendedModel,
        UserDtoModel,
        UserDtoExtendedModel,
        UserCreateRequest,
        UserCreateResponse,
        UserUpdateRequest,
        UserUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", constant = "true")
    @Mapping(target = "roleName", ignore = true)
    @Mapping(target = "workCategories", ignore = true)
    // FOR-02-07 — workerTypeId maps through by name; the outbound workerType ref is read-path only.
    @Mapping(target = "workerType", ignore = true)
    UserServiceExtendedModel toServiceExtendedModel(UserCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "roleName", ignore = true)
    @Mapping(target = "workCategories", ignore = true)
    @Mapping(target = "workerType", ignore = true)
    UserServiceExtendedModel toUpdateServiceExtendedModel(UserUpdateRequest source);
}
