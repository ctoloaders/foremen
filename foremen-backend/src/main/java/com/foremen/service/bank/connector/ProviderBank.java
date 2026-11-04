package com.foremen.service.bank.connector;

import java.util.Set;

/**
 * FOR-07-04 (Requirement 9.2): a {@code Supported_Bank} from the provider's live catalogue.
 *
 * @param code             provider bank code
 * @param name             bank name
 * @param logoUrl          logo URL; nullable
 * @param homeUrl          home page URL; nullable
 * @param status           provider status (e.g. {@code active})
 * @param mode             access mode ({@code oauth | web | api | file})
 * @param regulated        PSD2 channel
 * @param accountTypes     supported account types (e.g. {@code personal}, {@code business})
 * @param fetchScopes      supported fetch scopes (incl. holder info when supported)
 * @param automaticFetch   provider supports automatic fetch
 * @param maxConsentDays   maximum consent days; nullable
 * @param maxFetchInterval maximum fetch interval in days; nullable
 * @param requiresOwnKeys  CLIENT mode: bank needs own provider keys
 * @param testBank         fake / sandbox bank (shown only when fake providers are included)
 */
public record ProviderBank(
        String code,
        String name,
        String logoUrl,
        String homeUrl,
        String status,
        String mode,
        boolean regulated,
        Set<String> accountTypes,
        Set<String> fetchScopes,
        boolean automaticFetch,
        Integer maxConsentDays,
        Integer maxFetchInterval,
        boolean requiresOwnKeys,
        boolean testBank) {

    public ProviderBank {
        accountTypes = accountTypes == null ? Set.of() : Set.copyOf(accountTypes);
        fetchScopes = fetchScopes == null ? Set.of() : Set.copyOf(fetchScopes);
    }
}
