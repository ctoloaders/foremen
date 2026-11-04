package com.foremen.service.bank.connector;

import java.util.List;

/**
 * FOR-07-04 (Requirement 3.7): account holder data reported by the provider, used by the holder
 * matcher against the company profile.
 *
 * @param names  holder names (may be empty)
 * @param taxIds holder tax ids, e.g. NIP (may be empty)
 */
public record HolderInfo(List<String> names, List<String> taxIds) {

    public HolderInfo {
        names = names == null ? List.of() : List.copyOf(names);
        taxIds = taxIds == null ? List.of() : List.copyOf(taxIds);
    }

    /** {@code true} when neither names nor tax ids are present. */
    public boolean isEmpty() {
        return names.isEmpty() && taxIds.isEmpty();
    }
}
