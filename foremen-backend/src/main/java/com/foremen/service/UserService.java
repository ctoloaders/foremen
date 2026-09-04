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

    private static final Set<String> SUPPORTED_LOCALES = Set.of("ru", "pl");
    private static final String ADMIN_ROLE_CODE = "ADMIN";
    private static final String CLIENT_ROLE_CODE = "CLIENT";

    /**
     * Per-thread flag that signals the current {@code create(...)} call originated from the
     * dedicated client-registration path ({@link #createClient}). When set, {@link #validateCreate}
     * permits the server-resolved CLIENT role; otherwise the generic {@code POST /api/users} create
     * rejects a CLIENT role as defense in depth for FOR-03-05 Requirement 10.11.
     */
    private static final ThreadLocal<Boolean> CLIENT_CREATE_ALLOWED = ThreadLocal.withInitial(() -> Boolean.FALSE);

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
     *
     * <p>The CLIENT-role prohibition is enforced next as defense in depth (FOR-03-05 Requirement
     * 10.11): a {@code roleId} resolving to code {@code CLIENT} is rejected on the generic
     * {@code POST /api/users} create with HTTP 403, so CLIENT users can only be created through the
     * dedicated client-registration endpoint. The legitimate {@link #createClient} path sets a
     * per-thread flag that permits the server-resolved CLIENT role here.
     */
    @Override
    public void validateCreate(UserServiceExtendedModel model) {
        RoleEntity role = findRoleById(model.roleId());
        if (ADMIN_ROLE_CODE.equals(role.getCode())) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.user.admin.role.forbidden");
        }
        if (CLIENT_ROLE_CODE.equals(role.getCode()) && !CLIENT_CREATE_ALLOWED.get()) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.user.client.role.forbidden");
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
        RoleEntity targetRole = findRoleById(update.roleId());
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

    // --- Client registration (reuses the invite create path) ---

    /**
     * Creates a CLIENT user through the same framework {@code create(...)} path the admin create
     * uses, so the existing {@link #afterCreate(UserEntity)} &rarr;
     * {@link InviteService#issueInvite(UserEntity)} hook issues the invite token and dispatches the
     * client-portal invitation email (FOR-03-05 Requirements 10.4, 10.5).
     *
     * <p>The user is built as a {@link UserServiceExtendedModel} carrying the server-resolved
     * {@code clientRole} id; the {@code UserServiceMapper} resolves that id into the managed
     * {@link RoleEntity} on the created entity. Because the service-model exposes neither status nor
     * password, the persisted user keeps the {@link UserEntity} defaults — {@code status = INVITED}
     * and a {@code null} password hash — exactly like the invite flow (Requirement 10.4). Running
     * inside the transactional framework {@code create(...)}, an invite-token or mail failure rolls
     * back the whole create.
     *
     * <p>The supplied {@code clientRole} is expected to be the seeded {@code CLIENT} role resolved by
     * the caller ({@code ClientRegistrationService} via {@code RoleDao.findByCode("CLIENT")}); this
     * method does not itself resolve or restrict the role, it only fixes it on the created user.
     *
     * @param name       the client's display name
     * @param email      the client's email address (uniqueness enforced by {@link #validateCreate})
     * @param phone      the client's optional phone number
     * @param locale     the client's optional locale
     * @param clientRole the server-resolved CLIENT role to assign to the created user
     * @return the persisted, INVITED CLIENT {@link UserEntity}
     */
    public UserEntity createClient(String name, String email, String phone, String locale, RoleEntity clientRole) {
        // locale is optional (Req 10.1); the users.locale column is NOT NULL with a "ru" default,
        // so fall back to "ru" when the caller omits it or supplies a blank value.
        String resolvedLocale = (locale == null || locale.isBlank()) ? "ru" : locale;
        UserServiceExtendedModel model = new UserServiceExtendedModel(
                null,
                name,
                email,
                phone,
                clientRole.getId(),
                clientRole.getNameRU(),
                true,
                resolvedLocale,
                null
        );
        UserServiceExtendedModel created;
        CLIENT_CREATE_ALLOWED.set(Boolean.TRUE);
        try {
            created = create(model);
        } finally {
            CLIENT_CREATE_ALLOWED.remove();
        }
        return dao.findById(created.id())
                .orElseThrow(() -> new ForemenApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error.entity.not.found", created.id()));
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

    /**
     * Resolves a role by id and enforces the ADMIN-role prohibition as a hard CONFLICT guard
     * (BUG 1.6, Requirements 2.6, 3.3). Assigning the ADMIN role through the user-management API
     * is never permitted, so a resolved role whose code is {@link #ADMIN_ROLE_CODE} is rejected
     * with HTTP 409 and error key {@code error.user.admin.role.prohibited}. Non-ADMIN roles are
     * returned unchanged.
     *
     * <p>The create/update validation hooks intentionally do NOT route through this guard: they
     * use {@link #findRoleById(Long)} instead, because they apply their own ADMIN semantics —
     * a FORBIDDEN (403) prohibition on create/promotion with the {@code error.user.admin.role.forbidden}
     * key, while still allowing an existing ADMIN to remain ADMIN on an unchanged-role update
     * (Requirement 12.6). Keeping this CONFLICT guard separate lets both semantics coexist without
     * one overriding the other.
     *
     * <p>This method is retained as the single, directly-testable CONFLICT enforcement point for
     * the ADMIN-role prohibition (exercised by the FOR-02-07 bug-condition and preservation tests);
     * it is intentionally not invoked from the hooks above.
     */
    @SuppressWarnings("unused")
    private RoleEntity resolveRole(Long roleId) {
        RoleEntity role = findRoleById(roleId);
        if (ADMIN_ROLE_CODE.equals(role.getCode())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.user.admin.role.prohibited");
        }
        return role;
    }

    /**
     * Looks up a role by id without any ADMIN-role restriction: validates that an id was supplied
     * and that the role exists. Used by the create/update validation hooks, which apply their own
     * ADMIN-role rules (see {@link #validateCreate}/{@link #validateUpdate}) rather than the CONFLICT
     * guard in {@link #resolveRole(Long)}.
     */
    private RoleEntity findRoleById(Long roleId) {
        if (roleId == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.user.role.required");
        }
        return roleDao.findById(roleId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.BAD_REQUEST, "error.user.role.not.found", roleId));
    }
}
