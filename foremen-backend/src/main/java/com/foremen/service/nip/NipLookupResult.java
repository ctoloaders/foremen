package com.foremen.service.nip;

/**
 * FOR-11-00 (Requirement 6.1) — a company record returned by the NIP registry.
 *
 * @param nip      the NIP
 * @param name     company name
 * @param address  registered address
 * @param regon    REGON, may be {@code null}
 * @param krs      KRS number, may be {@code null}
 * @param registry the registry the record comes from
 */
public record NipLookupResult(String nip, String name, String address,
                              String regon, String krs, Registry registry) {

    public enum Registry { JDG, KRS, UNKNOWN }
}
