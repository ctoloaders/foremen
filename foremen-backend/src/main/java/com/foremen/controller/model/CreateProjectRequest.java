package com.foremen.controller.model;

import com.foremen.dao.model.ProjectStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request body of the custom project-creation endpoint {@code POST /api/projects} (FOR-04-13
 * Requirements 2.2, 2.4, 2.5). Carries the base project fields, the team {@code members} list, and
 * an optional {@code client} block. The whole request is processed in a single transaction by
 * {@code ProjectService.createProject(...)} (task 3.5); any step failure rolls the transaction back
 * (Requirement 2.9).
 *
 * <p>Bean validation covers the base-field constraints ({@code name} non-blank 1..255, {@code area}
 * within 0.01..999999999.99 when present) and the nested {@code members}/{@code client} validity;
 * the date-range rule ({@code endDate} not before {@code startDate}) and the "unknown member /
 * invalid role / duplicate" rules are enforced in the service layer.
 *
 * @param name             project name (required, 1..255 after trimming)
 * @param address          free-text address (optional, at most 500 characters)
 * @param googlePlaceId    Google place id (optional); when set with no supplied
 *                         {@code formattedAddress}/{@code latitude}/{@code longitude} the service
 *                         resolves them via the Google Places details lookup (Requirement 5.7)
 * @param formattedAddress canonical Google-formatted address (optional, at most 500 characters)
 * @param latitude         latitude (optional)
 * @param longitude        longitude (optional)
 * @param area             area in the range 0.01..999999999.99 when present (optional)
 * @param startDate        start date (optional, ISO)
 * @param endDate          end date (optional, ISO); must be on or after {@code startDate} when both present
 * @param status           lifecycle status (optional; defaults to {@code DRAFT} when null)
 * @param members          team members to assign to the created project
 * @param client           optional client block (existing CLIENT user or new client to create)
 */
public record CreateProjectRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 500) String address,
        String googlePlaceId,
        @Size(max = 500) String formattedAddress,
        BigDecimal latitude,
        BigDecimal longitude,
        @DecimalMin("0.01") @DecimalMax("999999999.99") BigDecimal area,
        LocalDate startDate,
        LocalDate endDate,
        ProjectStatus status,
        @Valid List<ProjectMemberInput> members,
        @Valid ClientBlock client
) {}
