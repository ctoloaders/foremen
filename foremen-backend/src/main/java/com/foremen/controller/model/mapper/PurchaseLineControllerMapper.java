package com.foremen.controller.model.mapper;

import java.util.List;

import org.mapstruct.Mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.PurchaseLineDtoModel;
import com.foremen.controller.model.PurchaseLineListResponseModel;
import com.foremen.service.model.PurchaseLineServiceModel;

/**
 * FOR-06-04 (Requirements 4.1, 9.1; design §5.3) — controller mapper for the reconciled
 * purchases-list DTOs, following the {@code ProcurementDocumentControllerMapper} read-path-only
 * convention.
 *
 * <p>The reconciliation write endpoints ({@code relink} / {@code clearMatch} / {@code classify} /
 * {@code clearClassification}) take their input through the focused request DTOs
 * ({@code RelinkRequestModel}, {@code ClassifyRequestModel}) and echo back a single
 * {@link PurchaseLineDtoModel}, so this mapper carries read-path conversions only — the generic
 * {@code ControllerToServiceMapper} create/update contract does not apply. Each row is copied field-
 * for-field from the already-resolved service model (both localized names + the derived tier percent
 * are filled by the reconciliation service / its service mapper).
 *
 * <ul>
 *   <li>{@code toDto} — one reconciled line row (the re-link / clear / classify echo).</li>
 *   <li>{@code toListResponse} — wraps the project-wide reconciled-list rows in the response envelope.</li>
 * </ul>
 */
@Mapper(config = ForemenMapperConfig.class)
public interface PurchaseLineControllerMapper {

    PurchaseLineDtoModel toDto(PurchaseLineServiceModel source);

    List<PurchaseLineDtoModel> toDtoList(List<PurchaseLineServiceModel> source);

    default PurchaseLineListResponseModel toListResponse(List<PurchaseLineServiceModel> source) {
        return new PurchaseLineListResponseModel(toDtoList(source));
    }
}
