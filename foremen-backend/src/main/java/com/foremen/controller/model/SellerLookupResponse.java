package com.foremen.controller.model;

/**
 * FOR-11-00 (Requirements 5.1-5.8) — answer of {@code GET /api/material-sellers/lookup}.
 * {@code seller} is set for DICTIONARY, {@code candidate} for REGISTRY (never persisted).
 */
public record SellerLookupResponse(String nip, Source source, MaterialSellerDtoModel seller,
                                   RegistryCandidate candidate, boolean registryAvailable) {

    public enum Source { DICTIONARY, REGISTRY, NONE }

    public record RegistryCandidate(String nip, String name, String address,
                                    String regon, String krs, String registry) {}
}
