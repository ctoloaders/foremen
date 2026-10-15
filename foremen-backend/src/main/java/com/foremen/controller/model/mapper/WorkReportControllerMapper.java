package com.foremen.controller.model.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.WorkReportCreateRequest;
import com.foremen.controller.model.WorkReportCreateResponse;
import com.foremen.controller.model.WorkReportDtoExtendedModel;
import com.foremen.controller.model.WorkReportDtoModel;
import com.foremen.controller.model.WorkReportUpdateRequest;
import com.foremen.controller.model.WorkReportUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.WorkReportServiceExtendedModel;
import com.foremen.service.model.WorkReportServiceModel;

/**
 * FOR-06-01 (Requirement 6.5; design §4.3) — controller mapper for the {@code WorkReport} DTOs,
 * following the {@code AssortmentPositionControllerMapper} convention.
 *
 * <p>The request/response records carry {@code projectId}/{@code workItemId}/{@code workerId}/
 * {@code performedFrom}/{@code performedTo}/{@code note}/{@code rooms}/{@code mediaRefs} that line
 * up by name with {@link WorkReportServiceExtendedModel}, so MapStruct maps them automatically; the
 * {@link WorkReportUpdateRequest} omits {@code projectId} (fixed at create time), so the update
 * write model's {@code projectId} is left {@code null} and the service keeps the existing project.
 * The two write conversions drop the base interface's {@code id}-ignore (the extended service
 * model's {@code id} is set only on read), mirroring {@code AssortmentPositionControllerMapper}.
 *
 * <p>The read conversions ({@code toDto}, {@code toExtendedDto}) copy the already-localized service
 * read model (names resolved in {@code WorkReportServiceMapper}) straight into the DTO records by
 * matching field names.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface WorkReportControllerMapper extends ControllerToServiceMapper<
        WorkReportServiceModel,
        WorkReportServiceExtendedModel,
        WorkReportDtoModel,
        WorkReportDtoExtendedModel,
        WorkReportCreateRequest,
        WorkReportCreateResponse,
        WorkReportUpdateRequest,
        WorkReportUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    WorkReportServiceExtendedModel toServiceExtendedModel(WorkReportCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "projectId", ignore = true)
    WorkReportServiceExtendedModel toUpdateServiceExtendedModel(WorkReportUpdateRequest source);
}
