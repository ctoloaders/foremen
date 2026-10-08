package com.foremen.service.document;

/**
 * FOR-05-08 (Requirement 3.3 / design decision 7): the update payload for the single
 * {@code CompanyProfile} row — the executor ({@code Wykonawca}) requisites.
 *
 * <p>{@link #fullName}, {@link #registeredAddress} and {@link #nip} are required (they are the
 * NOT-NULL columns and the minimum a merge needs); the remainder are optional.
 *
 * @param fullName            the company legal/full name (required)
 * @param registeredAddress   the registered seat address (required)
 * @param nip                 the tax id (required)
 * @param regon               the optional statistical id
 * @param representativeName  the optional signing representative name
 * @param representativeRole  the optional representative role/title
 * @param email               the optional contact email
 */
public record CompanyProfileInput(
        String fullName,
        String registeredAddress,
        String nip,
        String regon,
        String representativeName,
        String representativeRole,
        String email) {
}
