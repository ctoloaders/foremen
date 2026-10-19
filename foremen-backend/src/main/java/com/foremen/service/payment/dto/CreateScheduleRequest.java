package com.foremen.service.payment.dto;

import com.foremen.service.payment.ScheduleSource;

/**
 * FOR-06-07 (Requirements 1.1, 1.2): the create payload for a project's payment schedule. The
 * schedule is persisted in {@code DRAFT} with {@code basisGross} + {@code currency} seeded from the
 * project's {@code APPROVED} offer total / {@code OFFER_BASE} (the service reads them, the caller
 * never supplies the total). The UNIQUE {@code project_id} constraint bars a second schedule (R1.1).
 *
 * @param projectId the owning project id (required)
 * @param source    the creation path — {@code GENERATED} (a generator run will follow) or
 *                  {@code MANUAL} (tranches authored by hand); defaults to {@code MANUAL} when
 *                  {@code null} (R1.2)
 */
public record CreateScheduleRequest(
        Long projectId,
        ScheduleSource source) {
}
