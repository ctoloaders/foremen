package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.RefDto;
import com.foremen.controller.model.WorkMaterialConsumptionCreateRequest;
import com.foremen.controller.model.WorkMaterialConsumptionCreateResponse;
import com.foremen.controller.model.WorkMaterialConsumptionDtoExtendedModel;
import com.foremen.controller.model.WorkMaterialConsumptionDtoModel;
import com.foremen.controller.model.WorkMaterialConsumptionUpdateRequest;
import com.foremen.controller.model.WorkMaterialConsumptionUpdateResponse;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.model.WorkMaterialConsumptionServiceExtendedModel;
import com.foremen.service.model.WorkMaterialConsumptionServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Controller mapper for the work-material-consumption vertical (FOR-04-19, task 3.2).
 *
 * <p>An <b>interface</b> mapper (no injected seam is needed — there is no image/CDN concern). It
 * bridges the controller request/response records and the {@code WorkMaterialConsumptionService}
 * models.
 *
 * <p><b>Reads.</b> {@link #toDto} maps the read service model — whose localized {@link RefDto}
 * references, {@code branchLabel} and (later) computed {@code typeBatchRange}/{@code materials} are
 * populated by the {@code WorkMaterialConsumptionServiceMapper}/range resolver — through by field
 * name, and picks the DTO's single {@code materialType} from whichever of
 * {@code constructionMaterialType}/{@code finishingMaterialType} is set (exactly one, per the write
 * path's XOR + branch-match rule). {@link #toExtendedDto} additionally carries the raw reference ids
 * and BOTH raw {@code justificationRU}/{@code justificationPL} the edit form needs; the same
 * {@code materialType} pick applies.
 *
 * <p><b>Writes.</b> {@link #toServiceExtendedModel} / {@link #toUpdateServiceExtendedModel} carry the
 * raw reference ids, {@code branch}, {@code normQty}/{@code wastePct}, the raw justification pair and
 * the citation straight through; the localized references, {@code branchLabel}, the single localized
 * {@code justification} and the computed drill-in fields are read-only concerns left unset on the
 * write model (they are resolved on the read path). There is no {@code code}/{@code name} to protect,
 * so the update request maps like create.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface WorkMaterialConsumptionControllerMapper extends ControllerToServiceMapper<
        WorkMaterialConsumptionServiceModel,
        WorkMaterialConsumptionServiceExtendedModel,
        WorkMaterialConsumptionDtoModel,
        WorkMaterialConsumptionDtoExtendedModel,
        WorkMaterialConsumptionCreateRequest,
        WorkMaterialConsumptionCreateResponse,
        WorkMaterialConsumptionUpdateRequest,
        WorkMaterialConsumptionUpdateResponse> {

    /** Picks the single set analog-group type reference (construction XOR finishing). */
    default RefDto pickMaterialType(RefDto construction, RefDto finishing) {
        return construction != null ? construction : finishing;
    }

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workItem", ignore = true)
    @Mapping(target = "offerPackage", ignore = true)
    @Mapping(target = "materialUnit", ignore = true)
    @Mapping(target = "constructionMaterialType", ignore = true)
    @Mapping(target = "finishingMaterialType", ignore = true)
    @Mapping(target = "branchLabel", ignore = true)
    @Mapping(target = "justification", ignore = true)
    @Mapping(target = "typeBatchRange", ignore = true)
    @Mapping(target = "materials", ignore = true)
    WorkMaterialConsumptionServiceExtendedModel toServiceExtendedModel(
            WorkMaterialConsumptionCreateRequest source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workItem", ignore = true)
    @Mapping(target = "offerPackage", ignore = true)
    @Mapping(target = "materialUnit", ignore = true)
    @Mapping(target = "constructionMaterialType", ignore = true)
    @Mapping(target = "finishingMaterialType", ignore = true)
    @Mapping(target = "branchLabel", ignore = true)
    @Mapping(target = "justification", ignore = true)
    @Mapping(target = "typeBatchRange", ignore = true)
    @Mapping(target = "materials", ignore = true)
    WorkMaterialConsumptionServiceExtendedModel toUpdateServiceExtendedModel(
            WorkMaterialConsumptionUpdateRequest source);

    @Override
    @Mapping(target = "materialType",
            expression = "java(pickMaterialType(source.getConstructionMaterialType(), source.getFinishingMaterialType()))")
    WorkMaterialConsumptionDtoModel toDto(WorkMaterialConsumptionServiceModel source);

    @Override
    @Mapping(target = "materialType",
            expression = "java(pickMaterialType(source.getConstructionMaterialType(), source.getFinishingMaterialType()))")
    WorkMaterialConsumptionDtoExtendedModel toExtendedDto(WorkMaterialConsumptionServiceExtendedModel source);

    @Override
    @Mapping(target = "materialType",
            expression = "java(pickMaterialType(source.getConstructionMaterialType(), source.getFinishingMaterialType()))")
    WorkMaterialConsumptionCreateResponse toCreateResponse(WorkMaterialConsumptionServiceExtendedModel source);

    @Override
    @Mapping(target = "materialType",
            expression = "java(pickMaterialType(source.getConstructionMaterialType(), source.getFinishingMaterialType()))")
    WorkMaterialConsumptionUpdateResponse toUpdateResponse(WorkMaterialConsumptionServiceExtendedModel source);
}
