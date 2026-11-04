package com.foremen.service.document;

/**
 * Published by {@link CompanyProfileService#save(CompanyProfileInput)} after the single company profile
 * row was created or overwritten. FOR-07-04 (Requirement 3.10) listens to it to recompute the bank
 * account holder match against the new legal name / NIP.
 *
 * @param profileId the saved profile id
 */
public record CompanyProfileUpdatedEvent(Long profileId) {
}
