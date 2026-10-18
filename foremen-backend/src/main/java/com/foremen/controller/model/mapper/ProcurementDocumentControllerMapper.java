package com.foremen.controller.model.mapper;

import org.mapstruct.Mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.ProcurementDocumentDtoExtendedModel;
import com.foremen.controller.model.ProcurementDocumentDtoModel;
import com.foremen.controller.model.ProcurementDocumentReprocessResponse;
import com.foremen.controller.model.ProcurementDocumentUploadResponse;
import com.foremen.service.model.ProcurementDocumentServiceExtendedModel;
import com.foremen.service.model.ProcurementDocumentServiceModel;

/**
 * FOR-06-03 (Requirements 5.8, 10.1; design §5.6) — controller mapper for the
 * {@code ProcurementDocument} DTOs, following the {@code WorkReportControllerMapper} convention.
 *
 * <p>Procurement intake is file-driven: the document is created by the multipart upload endpoint
 * and re-run by the re-process endpoint (neither takes a JSON create/update body), so this mapper
 * carries <b>read-path only</b> conversions — the generic {@code ControllerToServiceMapper}
 * create/update request contract does not apply. Each conversion copies the already-resolved service
 * model (uploader name, image URLs, matched-material names, derived tier percents all filled by
 * {@code ProcurementDocumentService} / its service mapper) straight into the DTO record by matching
 * field names, including the nested {@code lines} list of {@code ProcurementLineModel} (identical on
 * both sides).
 *
 * <ul>
 *   <li>{@code toDto} — the project-scoped list row.</li>
 *   <li>{@code toExtendedDto} — the single-document read-only review projection.</li>
 *   <li>{@code toUploadResponse} — the upload echo (shape-compatible with the detail DTO).</li>
 *   <li>{@code toReprocessResponse} — the re-process echo (shape-compatible with the detail DTO).</li>
 * </ul>
 */
@Mapper(config = ForemenMapperConfig.class)
public interface ProcurementDocumentControllerMapper {

    ProcurementDocumentDtoModel toDto(ProcurementDocumentServiceModel source);

    ProcurementDocumentDtoExtendedModel toExtendedDto(
            ProcurementDocumentServiceExtendedModel source);

    ProcurementDocumentUploadResponse toUploadResponse(
            ProcurementDocumentServiceExtendedModel source);

    ProcurementDocumentReprocessResponse toReprocessResponse(
            ProcurementDocumentServiceExtendedModel source);
}
