package com.foremen.service.property;

import java.util.Optional;

import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.UserService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.UserServiceExtendedModel;
import com.foremen.service.model.mapper.UserServiceMapper;

import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the ADMIN-role assignment prohibition enforced by
 * {@link UserService} through the framework validation hooks {@code validateCreate}
 * and {@code validateUpdate} (Requirement 12).
 *
 * <p>The prohibition logic lives entirely in the hooks, which the CRUD framework invokes
 * as the first step of every create/update. These tests instantiate {@link UserService}
 * with Mockito mocks and call the hooks directly, which is exactly the branch the
 * framework routes through. {@code roleDao.findById} is stubbed to return a role with a
 * generated code; for updates an existing user carries a role of a generated existing
 * code. To isolate the ADMIN branch from the email/locale validations, generated emails
 * are unique ({@code dao.findByEmail} → empty) and the locale is a supported one.
 *
 * Property 25: ADMIN-role assignment prohibition invariant — Validates: Requirements 12.1, 12.2, 12.4, 12.6
 */
@Tag("Feature: FOR-03-01-jwt-auth, Property 25: ADMIN-role assignment prohibition invariant")
class AdminRoleProhibitionPropertyTest {

    private static final String ADMIN = "ADMIN";
    private static final String FORBIDDEN_CODE = "error.user.admin.role.forbidden";

    /** Role codes drawn from the system's known set (ADMIN plus non-ADMIN roles). */
    @Provide
    Arbitrary<String> roleCodes() {
        return Arbitraries.of(ADMIN, "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT");
    }

    /** Non-ADMIN role codes only. */
    @Provide
    Arbitrary<String> nonAdminRoleCodes() {
        return Arbitraries.of("MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT");
    }

    // --- Fixtures ---

    private record Fixture(UserService service, UserDao dao, RoleDao roleDao) {}

    private static Fixture newFixture() {
        UserDao dao = mock(UserDao.class);
        RoleDao roleDao = mock(RoleDao.class);
        UserServiceMapper mapper = mock(UserServiceMapper.class);
        AuditLogDao auditLogDao = mock(AuditLogDao.class);
        EntityManager entityManager = mock(EntityManager.class);
        // Email uniqueness passes: no existing user for any email.
        when(dao.findByEmail(any())).thenReturn(Optional.empty());
        UserService service = new UserService(dao, roleDao, mapper, auditLogDao, entityManager, null, null);
        return new Fixture(service, dao, roleDao);
    }

    private static RoleEntity role(long id, String code) {
        RoleEntity role = new RoleEntity();
        role.setId(id);
        role.setCode(code);
        return role;
    }

    private static UserServiceExtendedModel model(long roleId) {
        return new UserServiceExtendedModel(
                null, "Test User", "unique@example.com", null, roleId,
                null, true, "ru", null);
    }

    // Feature: FOR-03-01-jwt-auth, Property 25 (create branch)
    // For all create requests resolving to a role whose code equals ADMIN, validateCreate
    // throws ForemenApiException(403, error.user.admin.role.forbidden) and persists nothing.
    // Validates: Requirements 12.1, 12.4
    @Property(tries = 100)
    void createToAdminIsForbidden(@ForAll long roleId) {
        Fixture f = newFixture();
        when(f.roleDao().findById(anyLong())).thenReturn(Optional.of(role(roleId, ADMIN)));

        assertThatThrownBy(() -> f.service().validateCreate(model(roleId)))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getMessageCode()).isEqualTo(FORBIDDEN_CODE);
                });

        // No persistence occurred.
        verify(f.dao(), never()).save(any());
    }

    // Feature: FOR-03-01-jwt-auth, Property 25 (create branch, non-ADMIN)
    // For all create requests resolving to a non-ADMIN role, validateCreate does NOT raise
    // the ADMIN-prohibition exception.
    // Validates: Requirements 12.1, 12.6
    @Property(tries = 100)
    void createToNonAdminIsAllowed(@ForAll long roleId,
                                   @ForAll("nonAdminRoleCodes") String code) {
        Fixture f = newFixture();
        when(f.roleDao().findById(anyLong())).thenReturn(Optional.of(role(roleId, code)));

        assertThatCode(() -> f.service().validateCreate(model(roleId)))
                .doesNotThrowAnyException();
    }

    // Feature: FOR-03-01-jwt-auth, Property 25 (update branch, promotion)
    // For all update requests transitioning a user from a non-ADMIN role to ADMIN,
    // validateUpdate throws ForemenApiException(403, error.user.admin.role.forbidden).
    // Validates: Requirements 12.2, 12.4
    @Property(tries = 100)
    void promoteNonAdminToAdminIsForbidden(@ForAll long targetRoleId,
                                           @ForAll("nonAdminRoleCodes") String existingCode) {
        Fixture f = newFixture();
        when(f.roleDao().findById(anyLong())).thenReturn(Optional.of(role(targetRoleId, ADMIN)));

        UserEntity existing = new UserEntity();
        existing.setId(1L);
        existing.setEmail("unique@example.com");
        existing.setRole(role(99L, existingCode));

        assertThatThrownBy(() -> f.service().validateUpdate(existing, model(targetRoleId)))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getMessageCode()).isEqualTo(FORBIDDEN_CODE);
                });

        verify(f.dao(), never()).save(any());
    }

    // Feature: FOR-03-01-jwt-auth, Property 25 (update branch, ADMIN->ADMIN unchanged)
    // For an existing ADMIN whose role remains ADMIN, validateUpdate does NOT raise the
    // ADMIN-prohibition exception (unchanged-role case).
    // Validates: Requirements 12.6
    @Property(tries = 100)
    void adminToAdminUnchangedIsAllowed(@ForAll long targetRoleId) {
        Fixture f = newFixture();
        when(f.roleDao().findById(anyLong())).thenReturn(Optional.of(role(targetRoleId, ADMIN)));

        UserEntity existing = new UserEntity();
        existing.setId(1L);
        existing.setEmail("unique@example.com");
        existing.setRole(role(targetRoleId, ADMIN));

        assertThatCode(() -> f.service().validateUpdate(existing, model(targetRoleId)))
                .doesNotThrowAnyException();
    }

    // Feature: FOR-03-01-jwt-auth, Property 25 (update branch, non-ADMIN targets)
    // For all update requests whose target role is non-ADMIN (regardless of the existing
    // role), validateUpdate does NOT raise the ADMIN-prohibition exception.
    // Validates: Requirements 12.6
    @Property(tries = 100)
    void updateToNonAdminIsAllowed(@ForAll long targetRoleId,
                                   @ForAll("roleCodes") String existingCode,
                                   @ForAll("nonAdminRoleCodes") String targetCode) {
        Fixture f = newFixture();
        when(f.roleDao().findById(anyLong())).thenReturn(Optional.of(role(targetRoleId, targetCode)));

        UserEntity existing = new UserEntity();
        existing.setId(1L);
        existing.setEmail("unique@example.com");
        existing.setRole(role(99L, existingCode));

        assertThatCode(() -> f.service().validateUpdate(existing, model(targetRoleId)))
                .doesNotThrowAnyException();
    }
}
