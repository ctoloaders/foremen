package com.foremen.controller.model;

import com.foremen.dao.model.ProjectStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Response body of the custom project-creation endpoint {@code POST /api/projects} (FOR-04-13
 * Requirement 2.10): the created project including its generated {@code id} and persisted
 * {@code status}, plus the assigned team ({@code members}) and the derived {@code client} (the
 * single member whose {@code roleCode == "CLIENT"}, else {@code null}). Mirrors
 * {@link ProjectReadDto} so the frontend can render the freshly created project without a second
 * fetch.
 */
public record ProjectCreateResponse(
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
