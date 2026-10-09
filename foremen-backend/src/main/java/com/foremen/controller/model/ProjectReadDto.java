package com.foremen.controller.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foremen.dao.model.ProjectStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Single-record read projection DTO for {@code GET /api/projects/{id}} (FOR-04-13 Requirements 3.4,
 * and Change 2/3 of the design; extended by FOR-05-09 Requirement 19). Exposes the base project
 * fields plus the project team ({@code members}), the multi-client {@code clients} list (one entry
 * per CLIENT member, ordered by membership id ascending), and a derived {@code client} (the CLIENT
 * member with the lowest membership id = {@code clients[0]}, else {@code null}).
 *
 * <p>As with {@link ProjectListDto}, {@code client}/{@code clients} are <strong>derived</strong>
 * from {@code project_members} at projection time; there is no client/manager reference column.
 *
 * <p><b>FOR-05-09 Requirement 19 criterion 9 — CLIENT-caller suppression.</b> For a caller whose
 * Company_Role is CLIENT the projection nulls {@code members}, {@code clients}, and {@code client};
 * the class-level {@link JsonInclude}({@code NON_NULL}) then omits them from the payload entirely so
 * a CLIENT caller sees no member list. A non-CLIENT caller is unaffected (and always receives a
 * non-null {@code clients} list — empty when the project has no CLIENT member).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
        List<ProjectMemberSummaryDto> clients,
        ProjectMemberSummaryDto client
) {}
