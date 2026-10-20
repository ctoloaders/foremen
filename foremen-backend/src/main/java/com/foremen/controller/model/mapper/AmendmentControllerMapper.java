package com.foremen.controller.model.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.AmendmentCreateRequest;
import com.foremen.controller.model.AmendmentCreateResponse;
import com.foremen.controller.model.AmendmentDtoExtendedModel;
import com.foremen.controller.model.AmendmentDtoModel;
import com.foremen.controller.model.AmendmentUpdateRequest;
import com.foremen.controller.model.AmendmentUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.AmendmentServiceExtendedModel;
import com.foremen.service.model.AmendmentServiceModel;

/**
 * FOR-06-08 (Requirements 1.1, 1.4; design §Backend components) — controller mapper for the
 * {@code EstimateAmendment} DTOs, following the {@code WorkReportControllerMapper} /
 * {@code AssortmentPositionControllerMapper} convention.
 *
 * <p>The request/response records carry {@code projectId}/{@code note} (and, on the read side,
 * {@code seqNo}/{@code status}/{@code effectiveDate}/{@code lines}/{@code totalAddedValue}) that
 * line up by name with {@link AmendmentServiceExtendedModel} / {@link AmendmentServiceModel}, so
 * MapStruct maps them automatically; the {@link AmendmentLineDto} list elements line up with the
 * service-model {@code AmendmentLineServiceModel} fields. The {@link AmendmentUpdateRequest} omits
 * {@code projectId} (fixed at create time), so the update write model's {@code projectId} is left
 * {@code null} and the service keeps the existing project. The two write conversions drop the base
 * interface's {@code id}-ignore (the extended service model's {@code id} is set only on read),
 * mirroring {@code WorkReportControllerMapper}.
 *
 * <p>The read conversions ({@code toDto}, {@code toExtendedDto}) copy the already-localized service
 * read model (names resolved in {@code AmendmentServiceMapper}) straight into the DTO records by
 * matching field names.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface AmendmentControllerMapper extends ControllerToServiceMapper<
        AmendmentServiceModel,
        AmendmentServiceExtendedModel,
        AmendmentDtoModel,
        AmendmentDtoExtendedModel,
        AmendmentCreateRequest,
        AmendmentCreateResponse,
        AmendmentUpdateRequest,
        AmendmentUpdateResponse> {

    @Override
    @Mapping(target = "id", ignore = true)
    AmendmentServiceExtendedModel toServiceExtendedModel(AmendmentCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "projectId", ignore = true)
    AmendmentServiceExtendedModel toUpdateServiceExtendedModel(AmendmentUpdateRequest source);
}
