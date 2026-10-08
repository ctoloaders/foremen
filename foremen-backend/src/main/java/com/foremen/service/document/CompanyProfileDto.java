package com.foremen.service.document;

/**
 * FOR-05-08 (Requirement 3.3 / design decision 7, Open Question 1): the read model of the single
 * admin-managed {@code CompanyProfile} row — the executor ({@code Wykonawca}) requisites source read
 * by {@code CompanyRequisitesResolver} when merging a document body. Served by the ADMIN-only,
 * GLOBAL (not project-scoped) {@code CompanyProfile} requisites surface.
 *
 * @param id                  the profile row id (null when no profile has been saved yet)
 * @param fullName            the company legal/full name
 * @param registeredAddress   the registered seat address
 * @param nip                 the tax id
 * @param regon               the optional statistical id
 * @param representativeName  the optional signing representative name
 * @param representativeRole  the optional representative role/title
 * @param email               the optional contact email
 */
public record CompanyProfileDto(
        Long id,
        String fullName,
        String registeredAddress,
        String nip,
        String regon,
        String representativeName,
        String representativeRole,
        String email) {
}
