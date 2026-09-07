package com.foremen.controller.model;

import com.foremen.dao.model.ProjectStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Single-record read projection DTO for {@code GET /api/projects/{id}} (FOR-04-13 Requirements 3.4,
 * and Change 2/3 of the design). Exposes the base project fields plus the project team
 * ({@code members}) and a derived {@code client} (the single member whose {@code roleCode ==
 * "CLIENT"}, else {@code null}).
 *
 * <p>As with {@link ProjectListDto}, {@code client} is <strong>derived</strong> from
 * {@code project_members} at projection time; there is no client/manager reference column.
 */
public record ProjectReadDto(
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
