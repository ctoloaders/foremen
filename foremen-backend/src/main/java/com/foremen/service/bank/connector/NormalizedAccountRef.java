package com.foremen.service.bank.connector;

/**
 * FOR-07-04 (Requirement 4.1): identifies the provider account whose transactions are fetched.
 *
 * @param externalId provider account id ({@link NormalizedAccount#externalId()})
 */
public record NormalizedAccountRef(String externalId) {
}
