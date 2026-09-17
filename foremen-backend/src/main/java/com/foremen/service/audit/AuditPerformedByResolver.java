package com.foremen.service.audit;

import com.foremen.dao.UserDao;
import com.foremen.dao.model.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the stored audit {@code performedBy} value — persisted on the write side as
 * {@code String.valueOf(userId)} (the JWT {@code sub} claim) — into the acting user's
 * human-readable {@code name} at read time. The schema is unchanged (it still stores the id
 * string); resolution happens here via {@link UserDao}.
 *
 * <p>This is the single source of truth for the id&rarr;name semantics. Both the list endpoint
 * (via {@code AuditServiceMapper}, which delegates here) and the per-entity
 * {@code /{resource}/audit/{id}} endpoint use it so the two responses stay consistent.
 *
 * <p>Fallbacks preserve the original value when it cannot be resolved:
 * <ul>
 *     <li>{@code null}/blank &rarr; returned as-is;</li>
 *     <li>non-numeric legacy/system values (e.g. {@code "SYSTEM"}, an email) &rarr; returned as-is;</li>
 *     <li>a numeric id with no matching user &rarr; the original id string is returned.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AuditPerformedByResolver {

    private final UserDao userDao;

    public String resolveName(String performedBy) {
        if (performedBy == null || performedBy.isBlank()) {
            return performedBy;
        }
        final Long userId;
        try {
            userId = Long.valueOf(performedBy.trim());
        } catch (NumberFormatException e) {
            return performedBy;
        }
        return userDao.findById(userId).map(UserEntity::getName).orElse(performedBy);
    }
}
