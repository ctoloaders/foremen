package com.foremen.service.model;

import com.foremen.dao.model.ProjectStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Service extended model for {@link com.foremen.dao.model.ProjectEntity} (FOR-04-13). Mirrors the
 * persistable descriptive fields of the project. Generic create through this model is disabled at
 * the service layer (see {@code ProjectService#create}); projects are created only through the
 * custom {@code POST /api/projects} orchestrator. Generic read/update/delete continue to use this
 * model.
 */
public record ProjectServiceExtendedModel(Long id,
                                          String name,
                                          String address,
                                          String googlePlaceId,
                                          String formattedAddress,
                                          BigDecimal latitude,
                                          BigDecimal longitude,
                                          BigDecimal area,
                                          LocalDate startDate,
                                          LocalDate endDate,
                                          ProjectStatus status) {
}
