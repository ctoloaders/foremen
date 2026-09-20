package com.foremen.controller.model;

/**
 * A minimal localized reference payload for a related entity exposed on a list/read DTO.
 *
 * <p>Carries the referenced row's numeric {@code id} (stable, used by the frontend to build
 * reference filters / edit-form selections) and its localized display {@code name} (resolved to
 * {@code nameRU} for {@code ru}, else {@code namePL} — PL fallback — by the controller mapper).
 *
 * @param id   the numeric id of the referenced row
 * @param name the localized display name of the referenced row (null when the reference is absent)
 */
public record RefDto(Long id, String name) {}
