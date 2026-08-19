package com.foremen.mapper;

import org.mapstruct.Mapping;

public interface ControllerToServiceMapper<
        ServiceModel,
        ServiceExtendedModel,
        DtoModel,
        DtoExtendedModel,
        CreateRequestModel,
        CreateResponseModel,
        UpdateRequestModel,
        UpdateResponseModel> {

    @Mapping(target = "id", ignore = true)
    ServiceExtendedModel toServiceExtendedModel(CreateRequestModel source);

    ServiceExtendedModel toUpdateServiceExtendedModel(UpdateRequestModel source);

    DtoModel toDto(ServiceModel source);

    DtoExtendedModel toExtendedDto(ServiceExtendedModel source);

    CreateResponseModel toCreateResponse(ServiceExtendedModel source);

    UpdateResponseModel toUpdateResponse(ServiceExtendedModel source);
}
