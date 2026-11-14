package com.foremen.service.nip;

import java.util.Optional;

/**
 * FOR-11-00 (Requirements 6.1, 6.2, 6.6) — SPI for looking up a Polish company by NIP in an external
 * registry. Shipped disabled: {@link DisabledNipLookupService} is wired unless
 * {@code foremen.nip-lookup.enabled=true} and a base URL is configured.
 */
public interface NipLookupService {

    /**
     * Looks up a company by its normalized NIP.
     *
     * @param nip normalized 10-digit NIP
     * @return the registry record, or {@link Optional#empty()} when nothing was found
     * @throws NipLookupUnavailableException when the registry cannot be reached or answers with an error
     */
    Optional<NipLookupResult> lookup(String nip);

    /**
     * @return {@code true} when a real registry provider backs this service
     */
    boolean isConfigured();
}
