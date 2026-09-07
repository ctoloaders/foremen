package com.foremen.controller.model;

import com.foremen.dao.model.ProjectStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body of the generic project update {@code PUT /api/projects/{id}} (FOR-04-13
 * Requirement 3.3): the base project fields only. Team membership and the client block are not
 * editable through the generic update (deferred, Requirement 8.6).
 *
 * <p>Bean validation covers the base-field constraints ({@code name} non-blank 1..255, {@code area}
 * within 0.01..999999999.99 when present). The date-range rule ({@code endDate} not before
 * {@code startDate}) is enforced in the service layer (task 3.4).
 */
public record ProjectUpdateRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 500) String address,
        String googlePlaceId,
        @Size(max = 500) String formattedAddress,
        BigDecimal latitude,
        BigDecimal longitude,
        @DecimalMin("0.01") @DecimalMax("999999999.99") BigDecimal area,
        LocalDate startDate,
        LocalDate endDate,
        ProjectStatus status
) {}
