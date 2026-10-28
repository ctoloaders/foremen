package com.foremen.service.model;

import com.foremen.controller.model.RefDto;

import java.util.List;
import java.util.Map;

/**
 * Detailed user service model, carrying all fields the create / update / read flows move through the
 * CRUD framework.
 *
 * <p>FOR-05-10b (R1.1, R1.5) — two work-category fields ride on this model:
 * <ul>
 *   <li>{@code workCategoryIds} — the inbound, ordered, distinct id list on create / update
 *       (applied only for a WORKER user, D-worker-only); and</li>
 *   <li>{@code workCategories} — the outbound ordered {@code (id, localized name)} refs populated on
 *       the read path for a WORKER user (R1.5).</li>
 * </ul>
 * On the write path {@code workCategories} is left null; on the read path {@code workCategoryIds} is
 * left null. {@code UserService} owns the validation and persistence of the ids; the mapper
 * populates {@code workCategories} on read.
 */
public record UserServiceExtendedModel(
        Long id,
        String name,
        String email,
        String phone,
        Long roleId,
        String roleName,
        boolean active,
        String locale,
        Map<String, Object> displayPreferences,
        List<Long> workCategoryIds,
        List<RefDto> workCategories,
        // FOR-02-07 — worker-type: inbound id on the write path (applied only for a WORKER user,
        // D-worker-only), outbound (id, localized name) ref on the read path. Each is left null on
        // the opposite path, mirroring workCategoryIds / workCategories.
        Long workerTypeId,
        RefDto workerType
) {}
