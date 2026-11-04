package com.foremen.controller.model;

/**
 * FOR-07-03 (Requirement 5.1, 5.2) - body of {@code PUT /api/company-purchases/documents/{id}/project}.
 * A {@code null} {@code projectId} is rejected by the service with
 * {@code error.companyProcurement.relink.projectRequired} (clearing a project is out of scope).
 *
 * @param projectId the target {@code ACTIVE} project
 */
public record CompanyRelinkRequest(Long projectId) {
}
