package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.*;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.WorkerTypeServiceExtendedModel;
import com.foremen.service.model.WorkerTypeServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * FOR-05-06 — WorkerType controller&harr;service mapper. {@code active}/{@code base} default when the
 * request omits them ({@code active} defaults to {@code true}, {@code base} to {@code false}); the
 * service-layer validation enforces the single-base and share invariants.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface WorkerTypeControllerMapper extends ControllerToServiceMapper<
        WorkerTypeServiceModel,
        WorkerTypeServiceExtendedModel,
        WorkerTypeDtoModel,
        WorkerTypeDtoExtendedModel,
        WorkerTypeCreateRequest,
        WorkerTypeCreateResponse,
        WorkerTypeUpdateRequest,
        WorkerTypeUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "base", expression = "java(source.base() != null && source.base())")
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    WorkerTypeServiceExtendedModel toServiceExtendedModel(WorkerTypeCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "base", expression = "java(source.base() != null && source.base())")
    @Mapping(target = "active", expression = "java(source.active() == null || source.active())")
    WorkerTypeServiceExtendedModel toUpdateServiceExtendedModel(WorkerTypeUpdateRequest source);
}
