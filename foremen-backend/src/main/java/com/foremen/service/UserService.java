package com.foremen.service;

import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.UserServiceExtendedModel;
import com.foremen.service.model.UserServiceModel;
import com.foremen.service.model.mapper.UserServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
@Getter
@Transactional
public class UserService implements AdminService<
        UserServiceModel, UserServiceExtendedModel, UserEntity, Long> {

    private static final Set<String> SUPPORTED_LOCALES = Set.of("ru", "pl", "en");
    private static final String ADMIN_ROLE_CODE = "ADMIN";

    private final UserDao dao;
    private final RoleDao roleDao;
    private final UserServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final InviteService inviteService;
    private final ProjectAccessCache projectAccessCache;
    private final Class<UserEntity> daoModelClass = UserEntity.class;

    // --- Validation hooks (framework reuses default create()/update() for audit/snapshot) ---

    /**
     * Create-time validation. The ADMIN-role prohibition is enforced FIRST: assigning the
     * ADMIN role via the CRUD API is never permitted (12.1). The check runs regardless of
     * caller role and independent of any frontend, because the framework invokes this hook
     * for every create (12.3, 12.4).
     */
    @Override
    public void validateCreate(UserServiceExtendedModel model) {
        RoleEntity role = resolveRole(model.roleId());
        if (ADMIN_ROLE_CODE.equals(role.getCode())) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.user.admin.role.forbidden");
        }
        validateEmailUniqueness(model.email(), null);
        validateLocale(model.locale());
    }

    /**
     * Update-time validation. The ADMIN-role prohibition is enforced FIRST: promoting a
     * non-ADMIN user to ADMIN is never permitted (12.2). An unchanged role — including an
     * existing ADMIN remaining ADMIN — is allowed (12.6). Enforcement is independent of the
     * caller's role and any frontend, since the framework invokes this hook for every update
     * (12.3, 12.4).
     */
    @Override
    public void validateUpdate(UserEntity existing, UserServiceExtendedModel update) {
        RoleEntity targetRole = resolveRole(update.roleId());
        String existingRoleCode = existing.getRole() != null ? existing.getRole().getCode() : null;
        if (!ADMIN_ROLE_CODE.equals(existingRoleCode) && ADMIN_ROLE_CODE.equals(targetRole.getCode())) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.user.admin.role.forbidden");
        }
        validateEmailUniqueness(update.email(), existing.getId());
        validateLocale(update.locale());
    }

    /**
     * Overrides the framework update to evict the affected user's project-access cache entry
     * after a user update completes (Requirement 8.4). A user's role change can alter what
     * projects the user may see (e.g. ADMIN bypass), so the cached allowed-project-ids set is
     * invalidated so the next project-scoped read reloads it. Scope is limited to this touch
     * point; no FOR-03-08 controller migration is performed here.
     */
    @Override
    public UserServiceExtendedModel update(Long id, UserServiceExtendedModel model) {
        UserServiceExtendedModel result = AdminService.super.update(id, model);
        projectAccessCache.invalidate(id);
        return result;
    }

    /**
     * Post-create hook: issues exactly one invite token and dispatches the role-dependent
     * invitation email for the freshly created user (3.4, 3.7, 3.8). The user is always persisted
     * with User_Status INVITED and a null passwordHash because {@code UserServiceMapper} hard-codes
     * the status and the request DTOs expose none. Because the framework runs this hook inside the
     * transactional {@code create(...)}, a token-persist or mail failure propagates and rolls back
     * the user insert so neither the user nor the invite is retained (3.6).
     */
    @Override
    public void afterCreate(UserEntity user) {
        inviteService.issueInvite(user);
    }

    // --- Soft-delete (set active = false) ---

    @Override
    public void deleteById(Long id) {
        UserEntity user = dao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", id));

        UserEntity before = new UserEntity();
        before.setId(user.getId());
        before.setActive(user.isActive());

        user.setActive(false);
        dao.save(user);
        entityManager.flush();
        saveAudit(before, user, "DEACTIVATE");
    }

    // --- Validation helpers ---

    private void validateEmailUniqueness(String email, Long excludeId) {
        if (email == null) return;
        dao.findByEmail(email).ifPresent(existing -> {
            if (!existing.getId().equals(excludeId)) {
                throw new ForemenApiException(HttpStatus.CONFLICT, "error.user.email.already.exists", email);
            }
        });
    }

    private void validateLocale(String locale) {
        if (locale != null && !SUPPORTED_LOCALES.contains(locale)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.user.locale.invalid", locale);
        }
    }

    private RoleEntity resolveRole(Long roleId) {
        if (roleId == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.user.role.required");
        }
        return roleDao.findById(roleId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.BAD_REQUEST, "error.user.role.not.found", roleId));
    }
}
