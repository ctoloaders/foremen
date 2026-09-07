package com.foremen.service.model;

import com.foremen.dao.model.ProjectStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Service (list) model for {@link com.foremen.dao.model.ProjectEntity} (FOR-04-13). Carries the
 * project's descriptive, persistable fields only — the project team ({@code members}) and the
 * derived {@code client} are projected onto the controller DTOs
 * ({@link com.foremen.controller.model.ProjectListDto} /
 * {@link com.foremen.controller.model.ProjectReadDto}) from the read-only
 * {@code ProjectEntity.members} collection, never through this service model. {@code Project} is an
 * operational entity, so it uses {@code name}/{@code address} rather than the {@code nameRU}/
 * {@code namePL} i18n pair of dictionary entities.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectServiceModel {
    private Long id;
    private String name;
    private String address;
    private String googlePlaceId;
    private String formattedAddress;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal area;
    private LocalDate startDate;
    private LocalDate endDate;
    private ProjectStatus status;
}
