package com.foremen.controller.model;

import com.foremen.dao.model.ProjectStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Response body of the generic project update {@code PUT /api/projects/{id}} (FOR-04-13
 * Requirement 3.3): the updated project's base fields plus the current team ({@code members}) and
 * derived {@code client}. Mirrors {@link ProjectReadDto}.
 */
public record ProjectUpdateResponse(
        Long id,
        String name,
        String address,
        String googlePlaceId,
        String formattedAddress,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal area,
        LocalDate startDate,
        LocalDate endDate,
        ProjectStatus status,
        List<ProjectMemberSummaryDto> members,
        ProjectMemberSummaryDto client
) {}
