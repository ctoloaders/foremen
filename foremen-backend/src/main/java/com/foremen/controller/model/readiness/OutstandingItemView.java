package com.foremen.controller.model.readiness;

import java.util.Map;

import com.foremen.service.readiness.OutstandingItem;

/**
 * One localized outstanding-item description as returned to the client by
 * {@code GET /api/projects/{projectId}/readiness} (FOR-05-11 Requirement 1.4, 4.6, 7.3,
 * Decision D2/D10). It is the controller-facing projection of the service-level
 * {@link OutstandingItem}.
 *
 * <p>The item is resolved on the client: the API returns an i18n {@code descriptionKey} plus a
 * {@code params} map of placeholder values, and the UI renders the localized sentence and offers a
 * jump to {@code targetTab}. {@code params} carry only non-sensitive counts and identifiers, never
 * masked or monetary values (Requirement 4.8).
 *
 * @param descriptionKey the {@code readiness.*} i18n key describing the outstanding fact
 * @param params         a (possibly empty) map of placeholder values — counts/identifiers only
 * @param targetTab      the {@code WORKSPACE_TABS} key the UI jumps to for this item
 */
public record OutstandingItemView(
        String descriptionKey,
        Map<String, Object> params,
        String targetTab
) {

    /** Maps a service-level {@link OutstandingItem} to its REST response view. */
    public static OutstandingItemView from(OutstandingItem item) {
        return new OutstandingItemView(item.descriptionKey(), item.params(), item.targetTab());
    }
}
