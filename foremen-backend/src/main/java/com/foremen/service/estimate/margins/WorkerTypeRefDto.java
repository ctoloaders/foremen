package com.foremen.service.estimate.margins;

import java.math.BigDecimal;

/**
 * FOR-05-06 (design "Read-model DTOs") — a lightweight worker-type reference used as the Margins matrix
 * column headers (one per tier, in dictionary order). Carries only what the header + client recompute
 * need: the tier id, its localized name, whether it is the base tier, and its {@code tierPct} (the
 * offer share for the base tier, an uplift-on-base fraction otherwise). Mirrors the frontend
 * {@code WorkerTypeRef}.
 *
 * @param id      the worker type id
 * @param name    the worker type display name (localized at the read layer)
 * @param base    whether this is the base tier
 * @param tierPct the tier percentage (offer share for base, uplift-on-base fraction for non-base)
 */
public record WorkerTypeRefDto(
        Long id,
        String name,
        boolean base,
        BigDecimal tierPct) {
}
