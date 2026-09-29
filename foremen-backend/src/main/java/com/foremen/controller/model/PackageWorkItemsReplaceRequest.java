package com.foremen.controller.model;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/**
 * Replace payload for a position's PER-package work-item links (FOR-05-05 Wave 1b, #8). Carries the
 * full set of {@code (packageCode, workItemId)} items to apply: a {@code null} {@code workItemId}
 * clears the link for that package, a non-null one upserts it. Packages not named in {@code items}
 * are left untouched, so the request is a targeted replacement (which also covers a client-side
 * "propagate to all packages" — the client sends the propagated set).
 *
 * @param items the per-package link items to apply (each with a required package code)
 */
public record PackageWorkItemsReplaceRequest(
        @NotNull List<Item> items) {

    /**
     * One per-package link to apply: the target {@code packageCode} (required) and the
     * {@code workItemId} to link (or {@code null} to clear the link for that package).
     */
    public record Item(
            @NotNull String packageCode,
            Long workItemId) {
    }
}
