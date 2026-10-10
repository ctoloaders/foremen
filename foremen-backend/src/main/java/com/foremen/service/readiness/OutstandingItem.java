package com.foremen.service.readiness;

import java.util.Map;

/**
 * One localized description of a fact that keeps an area below 100% readiness (FOR-05-11
 * Requirement 1.4, Decision D2).
 *
 * <p>An Outstanding_Item is resolved on the client: the API returns an i18n {@code descriptionKey}
 * plus a {@code params} map of placeholder values, and the UI renders the localized sentence and
 * offers a jump to {@code targetTab}. A fully-ready area ({@link ReadinessState#DONE}, 100%) carries
 * an empty item list (Requirement 1.3).
 *
 * <p>{@code params} MUST carry only non-sensitive counts and identifiers, never masked or monetary
 * values (Requirement 4.8).
 *
 * @param descriptionKey the {@code readiness.*} i18n key describing the outstanding fact
 * @param params         a (possibly empty) map of placeholder values — counts/identifiers only
 * @param targetTab      the {@code WORKSPACE_TABS} key the UI jumps to for this item
 */
public record OutstandingItem(
        String descriptionKey,
        Map<String, Object> params,
        String targetTab
) {}
