package com.foremen.service.companyschedule;

/**
 * DB-free input to the pure {@code CrossProjectScheduleProjector} describing one ACTIVE FOREMAN
 * member of an {@link ProjectInput} (FOR-07-01 Requirement 1.3; design §"Pure projector"). The
 * projector synthesizes one Foreman_Span_Bar per {@code ForemanInput}, spanning the owning project
 * from its {@code startDate} to its {@code endDate}.
 *
 * <p>Carries only the foreman's identity — no cost-bearing field (Requirement 8.3). The span's
 * Company_Role is {@code FOREMAN} by construction, so no role code is carried here.
 *
 * @param userId   the foreman's {@code User} id
 * @param userName the foreman's name
 */
public record ForemanInput(long userId, String userName) {
}
